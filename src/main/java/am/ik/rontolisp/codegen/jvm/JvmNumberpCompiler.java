package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.jvm.Opcode;

/**
 * Compiles the {@code numberp} predicate.
 */
final class JvmNumberpCompiler {

	private JvmNumberpCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		int temp = ctx.allocTemp();
		ctx.emit(Opcode.ASTORE);
		ctx.emit(temp);
		ctx.emit(Opcode.ALOAD);
		ctx.emit(temp);
		ctx.emit(Opcode.INSTANCEOF);
		ctx.emitU2(ctx.numberClass.index());
		ctx.emit(Opcode.ALOAD);
		ctx.emit(temp);
		ctx.emit(Opcode.INSTANCEOF);
		ctx.emitU2(JvmEmitHelper.ratioArrayClass(ctx).index());
		ctx.emit(Opcode.IOR);
		if (ctx.usesComplex) {
			// A complex value is a number too -- but the holder test names the
			// travelling class, so it is emitted only for a complex-capable
			// program (`.kb/jvm-complex.md`).
			ctx.emit(Opcode.ALOAD);
			ctx.emit(temp);
			ctx.emit(Opcode.INSTANCEOF);
			ctx.emitU2(JvmComplexCompiler.complexClass(ctx).index());
			ctx.emit(Opcode.IOR);
		}
		JvmEmitHelper.emitBoolFromInt(ctx);
	}

}
