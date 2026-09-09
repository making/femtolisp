package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.rontolisp.LambdaLists;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.macro.LispMacroExpander;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.jvm.Opcode;

/**
 * Compiles the {@code (function name)} special form ({@code #'name} reader syntax) and
 * {@code (symbol-function 'name)}. Under the Lisp-2 model these are the only ways to
 * obtain a function as a first-class value: a named function resolves against the
 * compile-time function registry (user defuns and built-in wrappers) and compiles to a
 * closure {@code Object[]{Integer funcId}}; {@code (function (lambda ...))} compiles the
 * lambda value directly. In dynamic mode an unresolved name defers to the runtime via
 * {@code _eval('(function name), null)}.
 */
final class JvmFunctionFormCompiler {

	private JvmFunctionFormCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> parts = cons.toList();
		if (parts.size() != 2) {
			throw new UnsupportedOperationException(LispNames.FUNCTION + " expects exactly one argument");
		}
		LispVal designator = parts.get(1);
		if (designator instanceof LispCons lambdaForm && lambdaForm.car() instanceof LispSymbol op
				&& LispNames.LAMBDA.equals(op.name())) {
			JvmLambdaCompiler.compileValue(lambdaForm, ctx, className);
			return;
		}
		LispSymbol setfPlace = LambdaLists.setfFunctionPlaceName(designator);
		if (setfPlace != null) {
			// #'(setf name): the writer defun installed under the mangled internal name.
			compileNamed(LispMacroExpander.setfFunctionName(setfPlace.name()), ctx, className);
			return;
		}
		if (designator instanceof LispSymbol sym) {
			compileNamed(sym.name(), ctx, className);
			return;
		}
		throw new UnsupportedOperationException("Cannot compile: " + cons.print());
	}

	static void compileSymbolFunction(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> parts = cons.toList();
		if (parts.size() == 2 && parts.get(1) instanceof LispCons quoteForm && quoteForm.car() instanceof LispSymbol op
				&& LispNames.QUOTE.equals(op.name()) && ((LispCons) quoteForm.cdr()).car() instanceof LispSymbol sym) {
			compileNamed(sym.name(), ctx, className);
			return;
		}
		if (parts.size() != 2) {
			throw new IllegalArgumentException(
					LispNames.SYMBOL_FUNCTION + " expects exactly one argument: " + cons.print());
		}
		// A run-time name resolution BOXES the resolved funcId as a function value
		// (Object[]{Integer funcId}), so functionp answers t and the value prints
		// its registered name (.todo/750). When the eval runtime exists
		// (Ctx.evalStoreRef != null) its function namespace (_fenv, where
		// (setf (symbol-function ...)) installs and fmakunbound leaves its tombstone)
		// is probed first and decides on its own; without it _fenv is necessarily
		// empty (every writer forces the runtime), so the registry alone answers.
		// Otherwise the compiled-function registry (_lookup) answers, and a miss
		// signals exactly like the dispatchers' late binding. Only the function
		// namespace is read: a global VARIABLE holding a lambda is not a function
		// binding (the interpreter and SBCL signal for it).
		JvmExprCompiler.compileExpr(parts.get(1), ctx, className);
		int symSlot = ctx.allocTemp();
		ctx.emit(Opcode.ASTORE);
		ctx.emit(symSlot);
		int done;
		if (ctx.evalStoreRef != null) {
			ctx.emit(Opcode.ALOAD);
			ctx.emit(symSlot);
			ctx.emit(Opcode.GETSTATIC);
			ctx.emitU2(fenvField(ctx, className).index());
			ctx.emit(Opcode.INVOKESTATIC);
			ctx.emitU2(envLookupRef(ctx, className).index());
			ctx.emit(Opcode.DUP);
			int fenvMiss = branch(ctx, Opcode.IFNULL);
			ctx.emit(Opcode.CHECKCAST);
			ctx.emitU2(ctx.objectArrayClass.index());
			ctx.emit(Opcode.ICONST_1);
			ctx.emit(Opcode.AALOAD);
			ctx.emit(Opcode.DUP);
			int tombstone = branch(ctx, Opcode.IFNULL);
			done = branch(ctx, Opcode.GOTO);
			JvmEmitHelper.patchBranch(ctx, tombstone, ctx.code.size());
			ctx.emit(Opcode.POP);
			emitUndefinedFunctionThrow(symSlot, ctx);
			JvmEmitHelper.patchBranch(ctx, fenvMiss, ctx.code.size());
			ctx.emit(Opcode.POP);
		}
		else {
			done = -1;
		}
		ctx.emit(Opcode.ALOAD);
		ctx.emit(symSlot);
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(lookupRef(ctx, className).index());
		ctx.emit(Opcode.DUP);
		int registryMiss = branch(ctx, Opcode.IFNULL);
		ctx.emit(Opcode.ICONST_0);
		ctx.emit(Opcode.AALOAD);
		int idSlot = ctx.allocTemp();
		ctx.emit(Opcode.ASTORE);
		ctx.emit(idSlot);
		ctx.emit(Opcode.ICONST_1);
		ctx.emit(Opcode.ANEWARRAY);
		ctx.emitU2(ctx.objectClass.index());
		ctx.emit(Opcode.DUP);
		ctx.emit(Opcode.ICONST_0);
		ctx.emit(Opcode.ALOAD);
		ctx.emit(idSlot);
		ctx.emit(Opcode.AASTORE);
		int boxed = branch(ctx, Opcode.GOTO);
		JvmEmitHelper.patchBranch(ctx, registryMiss, ctx.code.size());
		ctx.emit(Opcode.POP);
		emitUndefinedFunctionThrow(symSlot, ctx);
		if (done >= 0) {
			JvmEmitHelper.patchBranch(ctx, done, ctx.code.size());
		}
		JvmEmitHelper.patchBranch(ctx, boxed, ctx.code.size());
	}

	static void compileNamed(String name, JvmLispCompiler.Ctx ctx, String className) {
		if (!ctx.functions.containsKey(name) && LispNames.isCarCdrComposition(name)) {
			// Synthesize (lambda (x) (cadr x)) so car/cdr compositions are first-class
			JvmLambdaCompiler.compileValue(carCdrLambda(name), ctx, className);
			return;
		}
		JvmLispCompiler.FunctionInfo fi = ctx.functions.get(name);
		if (fi != null) {
			// One of the two places a funcId becomes a callable VALUE, so it is where
			// the _invoke_N dispatchers learn they must carry a case for it.
			ctx.valueFuncIds.add(fi.funcId());
			ctx.emit(Opcode.ICONST_1);
			ctx.emit(Opcode.ANEWARRAY);
			ctx.emitU2(ctx.objectClass.index());
			ctx.emit(Opcode.DUP);
			ctx.emit(Opcode.ICONST_0);
			JvmEmitHelper.emitIntConst(ctx, fi.funcId());
			ctx.emit(Opcode.INVOKESTATIC);
			ctx.emitU2(ctx.integerValueOf.index());
			ctx.emit(Opcode.AASTORE);
		}
		else if (ctx.nestedDefunNames.contains(name) && ctx.globals.contains(name)) {
			// A defun nested inside a top-level let or a function body compiles to
			// (setq name (lambda ...)): the global variable already HOLDS the function
			// value. Before the dynamic fallback for the same reason the call site
			// checks it first (JvmFunctionCallCompiler).
			JvmExprCompiler.compileExpr(new am.ik.rontolisp.LispSymbol(name), ctx, className);
		}
		else if (ctx.dynamic) {
			JvmDynamicCallCompiler.compileFunctionRef(name, ctx, className);
		}
		else if (ctx.globals.contains(name)) {
			// A top-level (setq name (lambda ...)) the same way.
			JvmExprCompiler.compileExpr(new am.ik.rontolisp.LispSymbol(name), ctx, className);
		}
		else {
			throw new UnsupportedOperationException("Cannot compile: " + name);
		}
	}

	private static LispCons carCdrLambda(String name) {
		LispSymbol param = new LispSymbol("x");
		LispVal call = new LispCons(new LispSymbol(name), new LispCons(param, LispNil.INSTANCE));
		LispVal params = new LispCons(param, LispNil.INSTANCE);
		return new LispCons(new LispSymbol(LispNames.LAMBDA),
				new LispCons(params, new LispCons(call, LispNil.INSTANCE)));
	}

	/** Emits a branch with a placeholder offset; returns the position for patchBranch. */
	private static int branch(JvmLispCompiler.Ctx ctx, int opcode) {
		int pos = ctx.code.size();
		ctx.emit(opcode);
		ctx.emitU2(0);
		return pos;
	}

	private static am.ik.jvm.ConstantPool.FieldrefConstant fenvField(JvmLispCompiler.Ctx ctx, String className) {
		return ctx.cp.addFieldref(ctx.cp.addClass(ctx.cp.addUtf8(className)),
				ctx.cp.addNameAndType(ctx.cp.addUtf8("_fenv"), ctx.cp.addUtf8("Ljava/lang/Object;")));
	}

	private static am.ik.jvm.ConstantPool.MethodrefConstant envLookupRef(JvmLispCompiler.Ctx ctx, String className) {
		return ctx.cp.addMethodref(ctx.cp.addClass(ctx.cp.addUtf8(className)),
				ctx.cp.addNameAndType(ctx.cp.addUtf8("_envLookup"),
						ctx.cp.addUtf8("(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;")));
	}

	private static am.ik.jvm.ConstantPool.MethodrefConstant lookupRef(JvmLispCompiler.Ctx ctx, String className) {
		return ctx.cp.addMethodref(ctx.cp.addClass(ctx.cp.addUtf8(className)), ctx.cp
			.addNameAndType(ctx.cp.addUtf8("_lookup"), ctx.cp.addUtf8("(Ljava/lang/Object;)[Ljava/lang/Object;")));
	}

	// throw new RuntimeException("The function " + name + " is undefined") -- the
	// same late-binding failure the _invoke_N dispatchers raise for a symbol no
	// registry row answers.
	private static void emitUndefinedFunctionThrow(int nameSlot, JvmLispCompiler.Ctx ctx) {
		am.ik.jvm.ConstantPool.ClassConstant runtimeEx = ctx.cp.addClass(ctx.cp.addUtf8("java/lang/RuntimeException"));
		am.ik.jvm.ConstantPool.MethodrefConstant exCtor = ctx.cp.addMethodref(runtimeEx,
				ctx.cp.addNameAndType(ctx.cp.addUtf8("<init>"), ctx.cp.addUtf8("(Ljava/lang/String;)V")));
		am.ik.jvm.ConstantPool.MethodrefConstant concat = ctx.cp.addMethodref(ctx.stringClass, ctx.cp
			.addNameAndType(ctx.cp.addUtf8("concat"), ctx.cp.addUtf8("(Ljava/lang/String;)Ljava/lang/String;")));
		ctx.emit(Opcode.NEW);
		ctx.emitU2(runtimeEx.index());
		ctx.emit(Opcode.DUP);
		JvmEmitHelper.compileStringLiteral("The function ", ctx);
		ctx.emit(Opcode.ALOAD);
		ctx.emit(nameSlot);
		ctx.emit(Opcode.CHECKCAST);
		ctx.emitU2(ctx.stringClass.index());
		ctx.emit(Opcode.INVOKEVIRTUAL);
		ctx.emitU2(concat.index());
		JvmEmitHelper.compileStringLiteral(" is undefined", ctx);
		ctx.emit(Opcode.INVOKEVIRTUAL);
		ctx.emitU2(concat.index());
		ctx.emit(Opcode.INVOKESPECIAL);
		ctx.emitU2(exCtor.index());
		ctx.emit(Opcode.ATHROW);
	}

}
