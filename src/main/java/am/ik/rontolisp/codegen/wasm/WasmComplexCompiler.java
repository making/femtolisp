package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispComplex;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.macro.LispMacroExpander;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;

/**
 * Compiles the complex-number built-ins and the complex-aware steering of the real
 * operators. A complex value is a {@code TYPE_COMPLEX} struct (an {@code i32} tag plus
 * two real-part refs); construction canonicalizes through {@code _ccomplex}
 * ({@code WasmComplexRuntimeBuilder}), and every site below steers on
 * {@code LispMacroExpander.containsComplex} -- a {@code LispComplex} literal or a
 * {@code complex}/{@code conjugate} call in the tree -- the same syntactic gate the JVM
 * backend steers on ({@code .kb/jvm-complex.md}). A complex arriving only through a
 * variable beside a real operator takes that operator's ordinary path (documented in
 * {@code .kb/wasm-complex.md}), never a silently wrong number.
 *
 * <p>
 * Float parts are coerced through the ONE shared {@code _as_f64}
 * ({@code .kb/wasm-shared-coercion.md}); exact parts fold through the shared
 * {@code _rat_*} helpers, so funnels match real arithmetic. The transcendental formulas
 * reuse the backend's software cores, carrying their approximation error like every WASM
 * transcendental.
 */
final class WasmComplexCompiler {

	private WasmComplexCompiler() {
	}

	// A canonical complex literal in code position: the reader already demoted a
	// rational-zero imaginary part, so the parts emit plus struct.new is the value.
	static void compileLiteral(LispComplex c, WasmLispCompiler.Ctx ctx) {
		emitComplexTag(ctx);
		WasmExprCompiler.compileExpr(c.real(), ctx);
		WasmExprCompiler.compileExpr(c.imag(), ctx);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_COMPLEX);
	}

	// (complex re [im]): canonicalize through _ccomplex (a missing im is the i31
	// zero; a non-real part lands in _type_err_num there).
	static void compileComplex(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		if (args.size() < 2 || args.size() > 3) {
			throw new UnsupportedOperationException(
					"complex expects a real part and an optional imaginary part, got " + (args.size() - 1));
		}
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		if (args.size() == 3) {
			WasmExprCompiler.compileExpr(args.get(2), ctx);
		}
		else {
			constI32(ctx, 0);
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		}
		call(ctx, WasmLispCompiler.FUNC_C_COMPLEX);
	}

	// (complexp x): one ref.test, like floatp.
	static void compileComplexp(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		int slot = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(slot);
		emitTestComplex(ctx, slot);
		WasmEmitHelper.emitBoolFromI32(ctx);
	}

	// (realp x): every real tier (an exact integer, a ratio, a float) -- never a
	// complex.
	static void compileRealp(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		int slot = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(slot);
		emitTestReal(ctx, slot);
		WasmEmitHelper.emitBoolFromI32(ctx);
	}

	// (realpart x): field 0 for a complex, the value itself for a real, _type_err_num
	// ("Expected number, got: <prin1>") otherwise -- the interpreter's requireReal.
	static void compileRealpart(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		int slot = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(slot);
		emitTestComplex(ctx, slot);
		ctx.writer.write(Instruction.IF);
		ctx.writer.writeRefType(true, Type.EQ.code());
		pushPart(ctx, slot, 0);
		ctx.writer.write(Instruction.ELSE);
		emitTestReal(ctx, slot);
		ctx.writer.write(Instruction.IF);
		ctx.writer.writeRefType(true, Type.EQ.code());
		getLocal(ctx, slot);
		ctx.writer.write(Instruction.ELSE);
		getLocal(ctx, slot);
		call(ctx, WasmLispCompiler.FUNC_TYPE_ERR_NUM);
		ctx.writer.write(Instruction.UNREACHABLE);
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.END);
	}

	// (imagpart x): field 1 for a complex, a float zero for a float, the i31 zero
	// for any other real, _type_err_num otherwise (a float answers a float zero,
	// like SBCL).
	static void compileImagpart(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		int slot = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(slot);
		emitTestComplex(ctx, slot);
		ctx.writer.write(Instruction.IF);
		ctx.writer.writeRefType(true, Type.EQ.code());
		pushPart(ctx, slot, 1);
		ctx.writer.write(Instruction.ELSE);
		getLocal(ctx, slot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_FLOAT);
		ctx.writer.write(Instruction.IF);
		ctx.writer.writeRefType(true, Type.EQ.code());
		f64Const(ctx, 0.0);
		WasmExpCompiler.boxF64(ctx);
		ctx.writer.write(Instruction.ELSE);
		emitTestExactOrRatio(ctx, slot);
		ctx.writer.write(Instruction.IF);
		ctx.writer.writeRefType(true, Type.EQ.code());
		constI32(ctx, 0);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		ctx.writer.write(Instruction.ELSE);
		getLocal(ctx, slot);
		call(ctx, WasmLispCompiler.FUNC_TYPE_ERR_NUM);
		ctx.writer.write(Instruction.UNREACHABLE);
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.END);
	}

	// (conjugate x): a complex negates its imaginary part (a float part through
	// f64.neg, an exact part through _rat_sub from zero); a real answers itself; a
	// non-real lands in _type_err_num.
	static void compileConjugate(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		int slot = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(slot);
		emitTestComplex(ctx, slot);
		ctx.writer.write(Instruction.IF);
		ctx.writer.writeRefType(true, Type.EQ.code());
		int imSlot = ctx.allocTemp();
		pushPart(ctx, slot, 1);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(imSlot);
		int reSlot = ctx.allocTemp();
		pushPart(ctx, slot, 0);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(reSlot);
		getLocal(ctx, imSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_FLOAT);
		ctx.writer.write(Instruction.IF);
		ctx.writer.writeRefType(true, Type.EQ.code());
		getLocal(ctx, imSlot);
		WasmEmitHelper.castFloatGetF64(ctx);
		ctx.writer.write(Instruction.F64_NEG);
		WasmExpCompiler.boxF64(ctx);
		ctx.writer.write(Instruction.ELSE);
		constI32(ctx, 0);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		getLocal(ctx, imSlot);
		call(ctx, WasmLispCompiler.FUNC_RAT_SUB);
		ctx.writer.write(Instruction.END);
		int negSlot = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(negSlot);
		emitComplexTag(ctx);
		getLocal(ctx, reSlot);
		getLocal(ctx, negSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_COMPLEX);
		ctx.writer.write(Instruction.ELSE);
		emitTestReal(ctx, slot);
		ctx.writer.write(Instruction.IF);
		ctx.writer.writeRefType(true, Type.EQ.code());
		getLocal(ctx, slot);
		ctx.writer.write(Instruction.ELSE);
		getLocal(ctx, slot);
		call(ctx, WasmLispCompiler.FUNC_TYPE_ERR_NUM);
		ctx.writer.write(Instruction.UNREACHABLE);
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.END);
	}

	// (phase x): atan2(im, re) for a complex (the software atan core plus quadrant
	// assembly, carrying its approximation error); 0.0 for a non-negative real, pi
	// for a negative one. A non-number lands in _type_err_num through _as_f64.
	static void compilePhase(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		int slot = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(slot);
		emitTestComplex(ctx, slot);
		ctx.writer.write(Instruction.IF);
		ctx.writer.writeRefType(true, Type.EQ.code());
		int[] parts = emitPartsF64(ctx, slot);
		int out = ctx.allocTemp();
		emitAtan2Into(ctx, parts[1], parts[0], out);
		getLocal(ctx, out);
		ctx.writer.write(Instruction.ELSE);
		getLocal(ctx, slot);
		WasmEmitHelper.castFloatGetF64(ctx);
		int fSlot = ctx.allocTemp();
		WasmExpCompiler.boxF64(ctx);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(fSlot);
		WasmExpCompiler.unboxF64Local(ctx, fSlot);
		f64Const(ctx, 0.0);
		ctx.writer.write(Instruction.F64_LT);
		ctx.writer.write(Instruction.IF);
		ctx.writer.writeRefType(true, Type.EQ.code());
		f64Const(ctx, Math.PI);
		WasmExpCompiler.boxF64(ctx);
		ctx.writer.write(Instruction.ELSE);
		f64Const(ctx, 0.0);
		WasmExpCompiler.boxF64(ctx);
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.END);
	}

	// An n-ary + - * / form carrying a syntactic complex: unary - is _cneg, unary /
	// the reciprocal through _cdiv, otherwise a left fold through the pairwise
	// helper (the interpreter's loop order, so float rounding matches).
	static void compileArith(LispCons cons, WasmLispCompiler.Ctx ctx, int complexFunc) {
		List<LispVal> args = cons.toList();
		if (args.size() == 2) {
			if (complexFunc == WasmLispCompiler.FUNC_C_SUB) {
				WasmExprCompiler.compileExpr(args.get(1), ctx);
				call(ctx, WasmLispCompiler.FUNC_C_NEG);
				return;
			}
			if (complexFunc == WasmLispCompiler.FUNC_C_DIV) {
				constI32(ctx, 1);
				ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
				WasmExprCompiler.compileExpr(args.get(1), ctx);
				call(ctx, WasmLispCompiler.FUNC_C_DIV);
				return;
			}
		}
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		for (int i = 2; i < args.size(); i++) {
			WasmExprCompiler.compileExpr(args.get(i), ctx);
			call(ctx, complexFunc);
		}
	}

	// An = form carrying a syntactic complex, any arity: every adjacent pair must
	// compare equal, each pair part-wise when a complex is present at run time
	// (real pairs fall back to _rat_cmp_bits, which handles every real tier
	// including floats). A single argument is true.
	static void compileEqual(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		if (args.size() == 2) {
			WasmExprCompiler.compileExpr(args.get(1), ctx);
			ctx.writer.write(Instruction.DROP);
			WasmEmitHelper.emitTrue(ctx);
			return;
		}
		int count = args.size() - 1;
		int[] slots = new int[count];
		for (int i = 0; i < count; i++) {
			WasmExprCompiler.compileExpr(args.get(i + 1), ctx);
			slots[i] = ctx.allocTemp();
			ctx.writer.write(Instruction.SET_LOCAL);
			ctx.writer.writeUnsignedLeb128(slots[i]);
		}
		emitEqPair(ctx, slots[0], slots[1]);
		for (int i = 2; i < count; i++) {
			emitEqPair(ctx, slots[i - 1], slots[i]);
			ctx.writer.write(Instruction.I32_AND);
		}
		WasmEmitHelper.emitBoolFromI32(ctx);
	}

	// A /= form carrying a syntactic complex, any arity: every pair must compare
	// unequal (CL's /= is over all pairs, not just adjacent ones). A single
	// argument evaluates it for effect and answers true.
	static void compileNotEqual(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		if (args.size() == 2) {
			WasmExprCompiler.compileExpr(args.get(1), ctx);
			ctx.writer.write(Instruction.DROP);
			WasmEmitHelper.emitTrue(ctx);
			return;
		}
		int count = args.size() - 1;
		int[] slots = new int[count];
		for (int i = 0; i < count; i++) {
			WasmExprCompiler.compileExpr(args.get(i + 1), ctx);
			slots[i] = ctx.allocTemp();
			ctx.writer.write(Instruction.SET_LOCAL);
			ctx.writer.writeUnsignedLeb128(slots[i]);
		}
		boolean first = true;
		for (int i = 0; i < count; i++) {
			for (int j = i + 1; j < count; j++) {
				emitEqPair(ctx, slots[i], slots[j]);
				ctx.writer.write(Instruction.I32_EQZ);
				if (!first) {
					ctx.writer.write(Instruction.I32_AND);
				}
				first = false;
			}
		}
		WasmEmitHelper.emitBoolFromI32(ctx);
	}

	// An ordering form (< > <= >=) carrying a syntactic complex, any arity: every
	// adjacent pair must satisfy the relation; a complex in any pair lands in
	// _type_err_real ("Expected real number, got: <prin1>"). A single argument is
	// true.
	static void compileOrdering(LispCons cons, WasmLispCompiler.Ctx ctx, int mask) {
		List<LispVal> args = cons.toList();
		if (args.size() == 2) {
			WasmExprCompiler.compileExpr(args.get(1), ctx);
			ctx.writer.write(Instruction.DROP);
			WasmEmitHelper.emitTrue(ctx);
			return;
		}
		int count = args.size() - 1;
		int[] slots = new int[count];
		for (int i = 0; i < count; i++) {
			WasmExprCompiler.compileExpr(args.get(i + 1), ctx);
			slots[i] = ctx.allocTemp();
			ctx.writer.write(Instruction.SET_LOCAL);
			ctx.writer.writeUnsignedLeb128(slots[i]);
		}
		emitCmpPair(ctx, slots[0], slots[1], mask);
		for (int i = 2; i < count; i++) {
			emitCmpPair(ctx, slots[i - 1], slots[i], mask);
			ctx.writer.write(Instruction.I32_AND);
		}
		WasmEmitHelper.emitBoolFromI32(ctx);
	}

	// The min/max complex guard, over the two operand slots the caller already
	// filled: a complex in either lands in _type_err_real (the interpreter's
	// "Expected real number, got: <prin1>", caught as a simple-error on this
	// backend, like every instance-less throw).
	static void emitMinMaxComplexGuard(WasmLispCompiler.Ctx ctx, int aSlot, int bSlot) {
		emitTestComplex(ctx, aSlot);
		emitTestComplex(ctx, bSlot);
		ctx.writer.write(Instruction.I32_OR);
		ctx.writer.write(Instruction.IF, 0x40);
		emitTestComplex(ctx, aSlot);
		ctx.writer.write(Instruction.IF);
		ctx.writer.writeRefType(true, Type.EQ.code());
		getLocal(ctx, aSlot);
		ctx.writer.write(Instruction.ELSE);
		getLocal(ctx, bSlot);
		ctx.writer.write(Instruction.END);
		call(ctx, WasmLispCompiler.FUNC_TYPE_ERR_REAL);
		ctx.writer.write(Instruction.UNREACHABLE);
		ctx.writer.write(Instruction.END);
	}

	// (abs x) with a syntactic complex: the float modulus (a float even for exact
	// parts, like SBCL), scaled to avoid overflowing the squaring.
	static void compileAbs(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		int slot = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(slot);
		int[] parts = emitPartsF64(ctx, slot);
		emitHypotInto(ctx, parts[0], parts[1], slot);
		getLocal(ctx, slot);
	}

	// (sqrt x), always complex-aware: a complex roots through the float formula, a
	// negative real roots into the plane as (0, sqrt(-x)) -- the interpreter's
	// shape -- and anything else takes the native f64.sqrt into a float.
	static void compileSqrt(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		int slot = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(slot);
		emitTestComplex(ctx, slot);
		ctx.writer.write(Instruction.IF);
		ctx.writer.writeRefType(true, Type.EQ.code());
		int[] parts = emitPartsF64(ctx, slot);
		int reOut = ctx.allocTemp();
		int imOut = ctx.allocTemp();
		emitComplexSqrtInto(ctx, parts[0], parts[1], reOut, imOut);
		emitComplexTag(ctx);
		getLocal(ctx, reOut);
		getLocal(ctx, imOut);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_COMPLEX);
		ctx.writer.write(Instruction.ELSE);
		getLocal(ctx, slot);
		WasmEmitHelper.castFloatGetF64(ctx);
		int fSlot = ctx.allocTemp();
		WasmExpCompiler.boxF64(ctx);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(fSlot);
		WasmExpCompiler.unboxF64Local(ctx, fSlot);
		f64Const(ctx, 0.0);
		ctx.writer.write(Instruction.F64_LT);
		ctx.writer.write(Instruction.IF);
		ctx.writer.writeRefType(true, Type.EQ.code());
		f64Const(ctx, 0.0);
		WasmExpCompiler.boxF64(ctx);
		int zeroSlot = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(zeroSlot);
		WasmExpCompiler.unboxF64Local(ctx, fSlot);
		ctx.writer.write(Instruction.F64_NEG);
		ctx.writer.write(Instruction.F64_SQRT);
		WasmExpCompiler.boxF64(ctx);
		int rootSlot = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(rootSlot);
		emitComplexTag(ctx);
		getLocal(ctx, zeroSlot);
		getLocal(ctx, rootSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_COMPLEX);
		ctx.writer.write(Instruction.ELSE);
		WasmExpCompiler.unboxF64Local(ctx, fSlot);
		ctx.writer.write(Instruction.F64_SQRT);
		WasmExpCompiler.boxF64(ctx);
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.END);
	}

	// (expt base exp) with a syntactic complex: an i31 exponent over the exact
	// loop (repeated squaring through _cmul, a negative one through the exact
	// reciprocal -- an integer power over rational parts stays exact); anything
	// else through exp(w*log(z)) in floats.
	static void compileExpt(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		int baseSlot = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(baseSlot);
		WasmExprCompiler.compileExpr(args.get(2), ctx);
		int expSlot = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(expSlot);
		int rSlot = ctx.allocTemp();
		getLocal(ctx, expSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(Type.I31.code());
		ctx.writer.write(Instruction.IF, 0x40);
		emitExptExactLoop(ctx, baseSlot, expSlot, rSlot);
		ctx.writer.write(Instruction.ELSE);
		emitExptFloat(ctx, baseSlot, expSlot, rSlot);
		ctx.writer.write(Instruction.END);
		getLocal(ctx, rSlot);
	}

	// The eleven float unary functions over a syntactic complex, through the
	// backend's software cores (each formula the interpreter's, in f64).
	static void compileUnaryMath(LispCons cons, WasmLispCompiler.Ctx ctx, String name) {
		List<LispVal> args = cons.toList();
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		int slot = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(slot);
		int[] parts = emitPartsF64(ctx, slot);
		int reOut = ctx.allocTemp();
		int imOut = ctx.allocTemp();
		switch (name) {
			case am.ik.rontolisp.LispNames.EXP -> emitComplexExpInto(ctx, parts[0], parts[1], reOut, imOut);
			case am.ik.rontolisp.LispNames.LOG -> emitComplexLogInto(ctx, parts[0], parts[1], reOut, imOut);
			case am.ik.rontolisp.LispNames.SIN -> emitComplexSinInto(ctx, parts[0], parts[1], reOut, imOut);
			case am.ik.rontolisp.LispNames.COS -> emitComplexCosInto(ctx, parts[0], parts[1], reOut, imOut);
			case am.ik.rontolisp.LispNames.TAN -> emitComplexTanInto(ctx, parts[0], parts[1], reOut, imOut);
			case am.ik.rontolisp.LispNames.ASIN -> emitComplexAsinInto(ctx, parts[0], parts[1], reOut, imOut);
			case am.ik.rontolisp.LispNames.ACOS -> emitComplexAcosInto(ctx, parts[0], parts[1], reOut, imOut);
			case am.ik.rontolisp.LispNames.ATAN -> emitComplexAtanInto(ctx, parts[0], parts[1], reOut, imOut);
			case am.ik.rontolisp.LispNames.SINH -> emitComplexSinhInto(ctx, parts[0], parts[1], reOut, imOut);
			case am.ik.rontolisp.LispNames.COSH -> emitComplexCoshInto(ctx, parts[0], parts[1], reOut, imOut);
			case am.ik.rontolisp.LispNames.TANH -> emitComplexTanhInto(ctx, parts[0], parts[1], reOut, imOut);
			default -> throw new IllegalArgumentException("not a complex unary operator: " + name);
		}
		emitComplexTag(ctx);
		getLocal(ctx, reOut);
		getLocal(ctx, imOut);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_COMPLEX);
	}

	// Whether the form syntactically carries a certainly-complex producer -- the
	// steering gate for every site in this class.
	static boolean hasComplex(LispVal form) {
		return LispMacroExpander.containsComplex(form);
	}

	// Whether an = or /= form needs the complex-aware compilation (any operand
	// syntactically complex).
	static boolean hasComplexArgs(List<LispVal> args) {
		for (int i = 1; i < args.size(); i++) {
			if (LispMacroExpander.containsComplex(args.get(i))) {
				return true;
			}
		}
		return false;
	}

	// Pushes `slot is a TYPE_COMPLEX` as an i32.
	static void emitTestComplex(WasmLispCompiler.Ctx ctx, int slot) {
		getLocal(ctx, slot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_COMPLEX);
	}

	// Pushes `slot is a real number` (an exact integer, a ratio or a float -- never
	// a complex) as an i32.
	private static void emitTestReal(WasmLispCompiler.Ctx ctx, int slot) {
		getLocal(ctx, slot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(Type.I31.code());
		getLocal(ctx, slot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_BIGNUM);
		ctx.writer.write(Instruction.I32_OR);
		getLocal(ctx, slot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_BIGINT);
		ctx.writer.write(Instruction.I32_OR);
		getLocal(ctx, slot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_RATIO);
		ctx.writer.write(Instruction.I32_OR);
		getLocal(ctx, slot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_FLOAT);
		ctx.writer.write(Instruction.I32_OR);
	}

	// Pushes `slot is an exact integer or a ratio` (any real but a float or a
	// complex) as an i32.
	private static void emitTestExactOrRatio(WasmLispCompiler.Ctx ctx, int slot) {
		getLocal(ctx, slot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(Type.I31.code());
		getLocal(ctx, slot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_BIGNUM);
		ctx.writer.write(Instruction.I32_OR);
		getLocal(ctx, slot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_BIGINT);
		ctx.writer.write(Instruction.I32_OR);
		getLocal(ctx, slot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_RATIO);
		ctx.writer.write(Instruction.I32_OR);
	}

	// Pushes part 0 (real) or 1 (imaginary) of the value in slot: field 0/1 for a
	// complex, the value itself / the i31 zero for a real (a non-number is left
	// for the caller's funnel, like the runtime builder's).
	private static void pushPart(WasmLispCompiler.Ctx ctx, int slot, int field) {
		getLocal(ctx, slot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_COMPLEX);
		ctx.writer.write(Instruction.IF);
		ctx.writer.writeRefType(true, Type.EQ.code());
		getLocal(ctx, slot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_COMPLEX);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_COMPLEX);
		// Field 0 is the tag; part 0 (real) lives in field 1, part 1 in field 2.
		ctx.writer.writeUnsignedLeb128(field + 1);
		ctx.writer.write(Instruction.ELSE);
		if (field == 0) {
			getLocal(ctx, slot);
		}
		else {
			constI32(ctx, 0);
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		}
		ctx.writer.write(Instruction.END);
	}

	// The real and imaginary parts of the value in slot as f64 boxes in fresh
	// temps (each part through the shared _as_f64, so a non-number lands in
	// _type_err_num with itself as the culprit). Answers {reBox, imBox}.
	private static int[] emitPartsF64(WasmLispCompiler.Ctx ctx, int slot) {
		pushPart(ctx, slot, 0);
		WasmEmitHelper.castFloatGetF64(ctx);
		int reBox = boxF64Temp(ctx);
		pushPart(ctx, slot, 1);
		WasmEmitHelper.castFloatGetF64(ctx);
		int imBox = boxF64Temp(ctx);
		return new int[] { reBox, imBox };
	}

	// One = pair: part-wise _rat_cmp_bits equality when a complex is present at
	// run time, _rat_cmp_bits equality otherwise (it handles every real tier,
	// floats included). Leaves an i32.
	private static void emitEqPair(WasmLispCompiler.Ctx ctx, int aSlot, int bSlot) {
		emitTestComplex(ctx, aSlot);
		emitTestComplex(ctx, bSlot);
		ctx.writer.write(Instruction.I32_OR);
		ctx.writer.write(Instruction.IF);
		ctx.writer.write(Type.I32);
		pushPart(ctx, aSlot, 0);
		pushPart(ctx, bSlot, 0);
		call(ctx, WasmLispCompiler.FUNC_RAT_CMP_BITS);
		constI32(ctx, 2);
		ctx.writer.write(Instruction.I32_AND);
		pushPart(ctx, aSlot, 1);
		pushPart(ctx, bSlot, 1);
		call(ctx, WasmLispCompiler.FUNC_RAT_CMP_BITS);
		constI32(ctx, 2);
		ctx.writer.write(Instruction.I32_AND);
		ctx.writer.write(Instruction.I32_AND);
		ctx.writer.write(Instruction.ELSE);
		getLocal(ctx, aSlot);
		getLocal(ctx, bSlot);
		call(ctx, WasmLispCompiler.FUNC_RAT_CMP_BITS);
		constI32(ctx, 2);
		ctx.writer.write(Instruction.I32_AND);
		ctx.writer.write(Instruction.END);
	}

	// One ordering pair: a complex in either lands in _type_err_real, otherwise
	// _rat_cmp_bits masked. Leaves an i32.
	private static void emitCmpPair(WasmLispCompiler.Ctx ctx, int aSlot, int bSlot, int mask) {
		emitTestComplex(ctx, aSlot);
		emitTestComplex(ctx, bSlot);
		ctx.writer.write(Instruction.I32_OR);
		ctx.writer.write(Instruction.IF, 0x40);
		emitTestComplex(ctx, aSlot);
		ctx.writer.write(Instruction.IF);
		ctx.writer.writeRefType(true, Type.EQ.code());
		getLocal(ctx, aSlot);
		ctx.writer.write(Instruction.ELSE);
		getLocal(ctx, bSlot);
		ctx.writer.write(Instruction.END);
		call(ctx, WasmLispCompiler.FUNC_TYPE_ERR_REAL);
		ctx.writer.write(Instruction.UNREACHABLE);
		ctx.writer.write(Instruction.END);
		getLocal(ctx, aSlot);
		getLocal(ctx, bSlot);
		call(ctx, WasmLispCompiler.FUNC_RAT_CMP_BITS);
		constI32(ctx, mask);
		ctx.writer.write(Instruction.I32_AND);
	}

	// The exact expt loop: power is the i31 in expSlot (always int-range, so the
	// interpreter's exact path applies); the base squares through _cmul, a
	// negative power through the exact reciprocal first. Leaves the value in
	// rSlot.
	private static void emitExptExactLoop(WasmLispCompiler.Ctx ctx, int baseSlot, int expSlot, int rSlot) {
		// Negative exponent: base = _cdiv(1, base), power = -power.
		WasmMathHelper.getI32(ctx, expSlot);
		WasmMathHelper.constI32(ctx, 0);
		ctx.writer.write(Instruction.I32_LT_S);
		ctx.writer.write(Instruction.IF, 0x40);
		WasmMathHelper.constI32(ctx, 1);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		getLocal(ctx, baseSlot);
		call(ctx, WasmLispCompiler.FUNC_C_DIV);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(baseSlot);
		WasmMathHelper.constI32(ctx, 0);
		WasmMathHelper.getI32(ctx, expSlot);
		ctx.writer.write(Instruction.I32_SUB);
		WasmMathHelper.setI32(ctx, expSlot);
		ctx.writer.write(Instruction.END);
		// r = 1; while (power > 0) { if (power & 1) r = _cmul(r, base);
		// base = _cmul(base, base); power >>= 1 }
		WasmMathHelper.constI32(ctx, 1);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(rSlot);
		ctx.writer.write(Instruction.BLOCK, 0x40);
		ctx.writer.write(Instruction.LOOP, 0x40);
		WasmMathHelper.getI32(ctx, expSlot);
		WasmMathHelper.constI32(ctx, 0);
		ctx.writer.write(Instruction.I32_LE_S);
		ctx.writer.write(Instruction.BR_IF, 1);
		WasmMathHelper.getI32(ctx, expSlot);
		WasmMathHelper.constI32(ctx, 1);
		ctx.writer.write(Instruction.I32_AND);
		ctx.writer.write(Instruction.IF, 0x40);
		getLocal(ctx, rSlot);
		getLocal(ctx, baseSlot);
		call(ctx, WasmLispCompiler.FUNC_C_MUL);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(rSlot);
		ctx.writer.write(Instruction.END);
		getLocal(ctx, baseSlot);
		getLocal(ctx, baseSlot);
		call(ctx, WasmLispCompiler.FUNC_C_MUL);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(baseSlot);
		WasmMathHelper.getI32(ctx, expSlot);
		WasmMathHelper.constI32(ctx, 1);
		ctx.writer.write(Instruction.I32_SHR_S);
		WasmMathHelper.setI32(ctx, expSlot);
		ctx.writer.write(Instruction.BR, 0);
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.END);
	}

	// The float expt path: exp(w*log(z)) over the complex formulas below. Leaves
	// the boxed complex in rSlot.
	private static void emitExptFloat(WasmLispCompiler.Ctx ctx, int baseSlot, int expSlot, int rSlot) {
		int[] z = emitPartsF64(ctx, baseSlot);
		int[] w = emitPartsF64(ctx, expSlot);
		int lRe = ctx.allocTemp();
		int lIm = ctx.allocTemp();
		emitComplexLogInto(ctx, z[0], z[1], lRe, lIm);
		// e = (w0*l0 - w1*l1, w0*l1 + w1*l0), boxed per component.
		WasmExpCompiler.unboxF64Local(ctx, w[0]);
		WasmExpCompiler.unboxF64Local(ctx, lRe);
		ctx.writer.write(Instruction.F64_MUL);
		WasmExpCompiler.unboxF64Local(ctx, w[1]);
		WasmExpCompiler.unboxF64Local(ctx, lIm);
		ctx.writer.write(Instruction.F64_MUL);
		ctx.writer.write(Instruction.F64_SUB);
		int eRe = boxF64Temp(ctx);
		WasmExpCompiler.unboxF64Local(ctx, w[0]);
		WasmExpCompiler.unboxF64Local(ctx, lIm);
		ctx.writer.write(Instruction.F64_MUL);
		WasmExpCompiler.unboxF64Local(ctx, w[1]);
		WasmExpCompiler.unboxF64Local(ctx, lRe);
		ctx.writer.write(Instruction.F64_MUL);
		ctx.writer.write(Instruction.F64_ADD);
		int eIm = boxF64Temp(ctx);
		int oRe = ctx.allocTemp();
		int oIm = ctx.allocTemp();
		emitComplexExpInto(ctx, eRe, eIm, oRe, oIm);
		emitComplexTag(ctx);
		getLocal(ctx, oRe);
		getLocal(ctx, oIm);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_COMPLEX);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(rSlot);
	}

	// The principal square root of the f64 pair (reBox, imBox) into the boxed
	// out-slots: (re, im) unchanged at the origin (signed zeros preserved, like
	// the interpreter), otherwise t = sqrt((|re| + hypot)/2) with the
	// sign-dependent assembly.
	private static void emitComplexSqrtInto(WasmLispCompiler.Ctx ctx, int reBox, int imBox, int reOut, int imOut) {
		WasmExpCompiler.unboxF64Local(ctx, reBox);
		f64Const(ctx, 0.0);
		ctx.writer.write(Instruction.F64_EQ);
		WasmExpCompiler.unboxF64Local(ctx, imBox);
		f64Const(ctx, 0.0);
		ctx.writer.write(Instruction.F64_EQ);
		ctx.writer.write(Instruction.I32_AND);
		ctx.writer.write(Instruction.IF, 0x40);
		getLocal(ctx, reBox);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(reOut);
		getLocal(ctx, imBox);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(imOut);
		ctx.writer.write(Instruction.ELSE);
		int hBox = ctx.allocTemp();
		emitHypotInto(ctx, reBox, imBox, hBox);
		WasmExpCompiler.unboxF64Local(ctx, reBox);
		ctx.writer.write(Instruction.F64_ABS);
		WasmExpCompiler.unboxF64Local(ctx, hBox);
		ctx.writer.write(Instruction.F64_ADD);
		f64Const(ctx, 0.5);
		ctx.writer.write(Instruction.F64_MUL);
		ctx.writer.write(Instruction.F64_SQRT);
		int tBox = boxF64Temp(ctx);
		WasmExpCompiler.unboxF64Local(ctx, reBox);
		f64Const(ctx, 0.0);
		ctx.writer.write(Instruction.F64_GE);
		ctx.writer.write(Instruction.IF, 0x40);
		getLocal(ctx, tBox);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(reOut);
		WasmExpCompiler.unboxF64Local(ctx, imBox);
		WasmExpCompiler.unboxF64Local(ctx, tBox);
		f64Const(ctx, 2.0);
		ctx.writer.write(Instruction.F64_MUL);
		ctx.writer.write(Instruction.F64_DIV);
		WasmExpCompiler.boxF64(ctx);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(imOut);
		ctx.writer.write(Instruction.ELSE);
		WasmExpCompiler.unboxF64Local(ctx, imBox);
		ctx.writer.write(Instruction.F64_ABS);
		WasmExpCompiler.unboxF64Local(ctx, tBox);
		f64Const(ctx, 2.0);
		ctx.writer.write(Instruction.F64_MUL);
		ctx.writer.write(Instruction.F64_DIV);
		WasmExpCompiler.boxF64(ctx);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(reOut);
		WasmExpCompiler.unboxF64Local(ctx, tBox);
		WasmExpCompiler.unboxF64Local(ctx, imBox);
		ctx.writer.write(Instruction.F64_COPYSIGN);
		WasmExpCompiler.boxF64(ctx);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(imOut);
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.END);
	}

	// hypot(re, im) scaled to avoid overflowing the squaring: max*sqrt(1+(min/max)^2).
	private static void emitHypotInto(WasmLispCompiler.Ctx ctx, int reBox, int imBox, int out) {
		WasmExpCompiler.unboxF64Local(ctx, reBox);
		ctx.writer.write(Instruction.F64_ABS);
		int aBox = boxF64Temp(ctx);
		WasmExpCompiler.unboxF64Local(ctx, imBox);
		ctx.writer.write(Instruction.F64_ABS);
		int bBox = boxF64Temp(ctx);
		// max = a > b ? a : b; min = the other.
		WasmExpCompiler.unboxF64Local(ctx, aBox);
		WasmExpCompiler.unboxF64Local(ctx, bBox);
		ctx.writer.write(Instruction.F64_GT);
		ctx.writer.write(Instruction.IF);
		ctx.writer.write(Type.F64);
		WasmExpCompiler.unboxF64Local(ctx, aBox);
		ctx.writer.write(Instruction.ELSE);
		WasmExpCompiler.unboxF64Local(ctx, bBox);
		ctx.writer.write(Instruction.END);
		int maxBox = boxF64Temp(ctx);
		WasmExpCompiler.unboxF64Local(ctx, aBox);
		WasmExpCompiler.unboxF64Local(ctx, bBox);
		ctx.writer.write(Instruction.F64_GT);
		ctx.writer.write(Instruction.IF);
		ctx.writer.write(Type.F64);
		WasmExpCompiler.unboxF64Local(ctx, bBox);
		ctx.writer.write(Instruction.ELSE);
		WasmExpCompiler.unboxF64Local(ctx, aBox);
		ctx.writer.write(Instruction.END);
		int minBox = boxF64Temp(ctx);
		// min == 0 ? max : max*sqrt(1+(min/max)^2).
		WasmExpCompiler.unboxF64Local(ctx, minBox);
		f64Const(ctx, 0.0);
		ctx.writer.write(Instruction.F64_EQ);
		ctx.writer.write(Instruction.IF);
		ctx.writer.write(Type.F64);
		WasmExpCompiler.unboxF64Local(ctx, maxBox);
		ctx.writer.write(Instruction.ELSE);
		WasmExpCompiler.unboxF64Local(ctx, minBox);
		WasmExpCompiler.unboxF64Local(ctx, maxBox);
		ctx.writer.write(Instruction.F64_DIV);
		WasmExpCompiler.unboxF64Local(ctx, minBox);
		WasmExpCompiler.unboxF64Local(ctx, maxBox);
		ctx.writer.write(Instruction.F64_DIV);
		ctx.writer.write(Instruction.F64_MUL);
		f64Const(ctx, 1.0);
		ctx.writer.write(Instruction.F64_ADD);
		ctx.writer.write(Instruction.F64_SQRT);
		WasmExpCompiler.unboxF64Local(ctx, maxBox);
		ctx.writer.write(Instruction.F64_MUL);
		ctx.writer.write(Instruction.END);
		WasmExpCompiler.boxF64(ctx);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(out);
	}

	// log(z) = (ln(hypot), atan2(im, re)) into the boxed out-slots.
	private static void emitComplexLogInto(WasmLispCompiler.Ctx ctx, int reBox, int imBox, int reOut, int imOut) {
		int hBox = ctx.allocTemp();
		emitHypotInto(ctx, reBox, imBox, hBox);
		WasmExpCompiler.unboxF64Local(ctx, hBox);
		int xSlot = ctx.allocTemp();
		int mSlot = ctx.allocTemp();
		int eSlot = ctx.allocTemp();
		WasmLogCompiler.emitLogCore(ctx, xSlot, mSlot, eSlot);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(reOut);
		emitAtan2Into(ctx, imBox, reBox, imOut);
	}

	// exp(z) = (e^re*cos(im), e^re*sin(im)) into the boxed out-slots.
	private static void emitComplexExpInto(WasmLispCompiler.Ctx ctx, int reBox, int imBox, int reOut, int imOut) {
		WasmExpCompiler.unboxF64Local(ctx, reBox);
		int xSlot = ctx.allocTemp();
		int kSlot = ctx.allocTemp();
		int accSlot = ctx.allocTemp();
		WasmExpCompiler.emitExpCore(ctx, xSlot, kSlot, accSlot);
		int eBox = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(eBox);
		int cosBox = ctx.allocTemp();
		callCosInto(ctx, imBox, cosBox);
		int sinBox = ctx.allocTemp();
		callSinInto(ctx, imBox, sinBox);
		WasmExpCompiler.unboxF64Local(ctx, eBox);
		WasmExpCompiler.unboxF64Local(ctx, cosBox);
		ctx.writer.write(Instruction.F64_MUL);
		WasmExpCompiler.boxF64(ctx);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(reOut);
		WasmExpCompiler.unboxF64Local(ctx, eBox);
		WasmExpCompiler.unboxF64Local(ctx, sinBox);
		ctx.writer.write(Instruction.F64_MUL);
		WasmExpCompiler.boxF64(ctx);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(imOut);
	}

	// sin(z) = (sin(re)*cosh(im), cos(re)*sinh(im)) into the boxed out-slots.
	private static void emitComplexSinInto(WasmLispCompiler.Ctx ctx, int reBox, int imBox, int reOut, int imOut) {
		int sinRe = ctx.allocTemp();
		callSinInto(ctx, reBox, sinRe);
		int cosRe = ctx.allocTemp();
		callCosInto(ctx, reBox, cosRe);
		int sinhIm = ctx.allocTemp();
		callSinhInto(ctx, imBox, sinhIm);
		int coshIm = ctx.allocTemp();
		callCoshInto(ctx, imBox, coshIm);
		WasmExpCompiler.unboxF64Local(ctx, sinRe);
		WasmExpCompiler.unboxF64Local(ctx, coshIm);
		ctx.writer.write(Instruction.F64_MUL);
		WasmExpCompiler.boxF64(ctx);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(reOut);
		WasmExpCompiler.unboxF64Local(ctx, cosRe);
		WasmExpCompiler.unboxF64Local(ctx, sinhIm);
		ctx.writer.write(Instruction.F64_MUL);
		WasmExpCompiler.boxF64(ctx);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(imOut);
	}

	// cos(z) = (cos(re)*cosh(im), -sin(re)*sinh(im)) into the boxed out-slots.
	private static void emitComplexCosInto(WasmLispCompiler.Ctx ctx, int reBox, int imBox, int reOut, int imOut) {
		int sinRe = ctx.allocTemp();
		callSinInto(ctx, reBox, sinRe);
		int cosRe = ctx.allocTemp();
		callCosInto(ctx, reBox, cosRe);
		int sinhIm = ctx.allocTemp();
		callSinhInto(ctx, imBox, sinhIm);
		int coshIm = ctx.allocTemp();
		callCoshInto(ctx, imBox, coshIm);
		WasmExpCompiler.unboxF64Local(ctx, cosRe);
		WasmExpCompiler.unboxF64Local(ctx, coshIm);
		ctx.writer.write(Instruction.F64_MUL);
		WasmExpCompiler.boxF64(ctx);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(reOut);
		WasmExpCompiler.unboxF64Local(ctx, sinRe);
		WasmExpCompiler.unboxF64Local(ctx, sinhIm);
		ctx.writer.write(Instruction.F64_MUL);
		ctx.writer.write(Instruction.F64_NEG);
		WasmExpCompiler.boxF64(ctx);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(imOut);
	}

	// tan(z) = sin(z)/cos(z) in f64 assembly.
	private static void emitComplexTanInto(WasmLispCompiler.Ctx ctx, int reBox, int imBox, int reOut, int imOut) {
		int sRe = ctx.allocTemp();
		int sIm = ctx.allocTemp();
		emitComplexSinInto(ctx, reBox, imBox, sRe, sIm);
		int cRe = ctx.allocTemp();
		int cIm = ctx.allocTemp();
		emitComplexCosInto(ctx, reBox, imBox, cRe, cIm);
		emitComplexDivF64(ctx, sRe, sIm, cRe, cIm, reOut, imOut);
	}

	// asin(z) = -i*log(i*z + sqrt(1-z^2)).
	private static void emitComplexAsinInto(WasmLispCompiler.Ctx ctx, int reBox, int imBox, int reOut, int imOut) {
		// z2 = (re^2-im^2, 2*re*im), boxed per component.
		WasmExpCompiler.unboxF64Local(ctx, reBox);
		WasmExpCompiler.unboxF64Local(ctx, reBox);
		ctx.writer.write(Instruction.F64_MUL);
		WasmExpCompiler.unboxF64Local(ctx, imBox);
		WasmExpCompiler.unboxF64Local(ctx, imBox);
		ctx.writer.write(Instruction.F64_MUL);
		ctx.writer.write(Instruction.F64_SUB);
		int z2Re = boxF64Temp(ctx);
		f64Const(ctx, 2.0);
		WasmExpCompiler.unboxF64Local(ctx, reBox);
		ctx.writer.write(Instruction.F64_MUL);
		WasmExpCompiler.unboxF64Local(ctx, imBox);
		ctx.writer.write(Instruction.F64_MUL);
		int z2Im = boxF64Temp(ctx);
		// s = sqrt(1-z2re, -z2im).
		f64Const(ctx, 1.0);
		WasmExpCompiler.unboxF64Local(ctx, z2Re);
		ctx.writer.write(Instruction.F64_SUB);
		int sArgRe = boxF64Temp(ctx);
		WasmExpCompiler.unboxF64Local(ctx, z2Im);
		ctx.writer.write(Instruction.F64_NEG);
		int sArgIm = boxF64Temp(ctx);
		int sRe = ctx.allocTemp();
		int sIm = ctx.allocTemp();
		emitComplexSqrtInto(ctx, sArgRe, sArgIm, sRe, sIm);
		// l = log(-im+s0, re+s1); answer (l1, -l0).
		WasmExpCompiler.unboxF64Local(ctx, sRe);
		WasmExpCompiler.unboxF64Local(ctx, imBox);
		ctx.writer.write(Instruction.F64_SUB);
		int lArgRe = boxF64Temp(ctx);
		WasmExpCompiler.unboxF64Local(ctx, reBox);
		WasmExpCompiler.unboxF64Local(ctx, sIm);
		ctx.writer.write(Instruction.F64_ADD);
		int lArgIm = boxF64Temp(ctx);
		int lRe = ctx.allocTemp();
		int lIm = ctx.allocTemp();
		emitComplexLogInto(ctx, lArgRe, lArgIm, lRe, lIm);
		getLocal(ctx, lIm);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(reOut);
		WasmExpCompiler.unboxF64Local(ctx, lRe);
		ctx.writer.write(Instruction.F64_NEG);
		WasmExpCompiler.boxF64(ctx);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(imOut);
	}

	// acos(z) = (pi/2 - asin0, -asin1).
	private static void emitComplexAcosInto(WasmLispCompiler.Ctx ctx, int reBox, int imBox, int reOut, int imOut) {
		int aRe = ctx.allocTemp();
		int aIm = ctx.allocTemp();
		emitComplexAsinInto(ctx, reBox, imBox, aRe, aIm);
		f64Const(ctx, Math.PI / 2);
		WasmExpCompiler.unboxF64Local(ctx, aRe);
		ctx.writer.write(Instruction.F64_SUB);
		WasmExpCompiler.boxF64(ctx);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(reOut);
		WasmExpCompiler.unboxF64Local(ctx, aIm);
		ctx.writer.write(Instruction.F64_NEG);
		WasmExpCompiler.boxF64(ctx);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(imOut);
	}

	// atan(z) = (i/2)*(log(1-i*z) - log(1+i*z)).
	private static void emitComplexAtanInto(WasmLispCompiler.Ctx ctx, int reBox, int imBox, int reOut, int imOut) {
		// l1 = log(1+im, -re).
		f64Const(ctx, 1.0);
		WasmExpCompiler.unboxF64Local(ctx, imBox);
		ctx.writer.write(Instruction.F64_ADD);
		int l1ArgRe = boxF64Temp(ctx);
		WasmExpCompiler.unboxF64Local(ctx, reBox);
		ctx.writer.write(Instruction.F64_NEG);
		int l1ArgIm = boxF64Temp(ctx);
		int l1Re = ctx.allocTemp();
		int l1Im = ctx.allocTemp();
		emitComplexLogInto(ctx, l1ArgRe, l1ArgIm, l1Re, l1Im);
		// l2 = log(1-im, re).
		f64Const(ctx, 1.0);
		WasmExpCompiler.unboxF64Local(ctx, imBox);
		ctx.writer.write(Instruction.F64_SUB);
		int l2ArgRe = boxF64Temp(ctx);
		int l2ArgIm = reBox;
		int l2Re = ctx.allocTemp();
		int l2Im = ctx.allocTemp();
		emitComplexLogInto(ctx, l2ArgRe, l2ArgIm, l2Re, l2Im);
		// answer ((l2.1-l1.1)/2, (l1.0-l2.0)/2).
		WasmExpCompiler.unboxF64Local(ctx, l2Im);
		WasmExpCompiler.unboxF64Local(ctx, l1Im);
		ctx.writer.write(Instruction.F64_SUB);
		f64Const(ctx, 2.0);
		ctx.writer.write(Instruction.F64_DIV);
		WasmExpCompiler.boxF64(ctx);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(reOut);
		WasmExpCompiler.unboxF64Local(ctx, l1Re);
		WasmExpCompiler.unboxF64Local(ctx, l2Re);
		ctx.writer.write(Instruction.F64_SUB);
		f64Const(ctx, 2.0);
		ctx.writer.write(Instruction.F64_DIV);
		WasmExpCompiler.boxF64(ctx);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(imOut);
	}

	// sinh(z) = (sinh(re)*cos(im), cosh(re)*sin(im)) into the boxed out-slots.
	private static void emitComplexSinhInto(WasmLispCompiler.Ctx ctx, int reBox, int imBox, int reOut, int imOut) {
		int sinhRe = ctx.allocTemp();
		callSinhInto(ctx, reBox, sinhRe);
		int coshRe = ctx.allocTemp();
		callCoshInto(ctx, reBox, coshRe);
		int sinIm = ctx.allocTemp();
		callSinInto(ctx, imBox, sinIm);
		int cosIm = ctx.allocTemp();
		callCosInto(ctx, imBox, cosIm);
		WasmExpCompiler.unboxF64Local(ctx, sinhRe);
		WasmExpCompiler.unboxF64Local(ctx, cosIm);
		ctx.writer.write(Instruction.F64_MUL);
		WasmExpCompiler.boxF64(ctx);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(reOut);
		WasmExpCompiler.unboxF64Local(ctx, coshRe);
		WasmExpCompiler.unboxF64Local(ctx, sinIm);
		ctx.writer.write(Instruction.F64_MUL);
		WasmExpCompiler.boxF64(ctx);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(imOut);
	}

	// cosh(z) = (cosh(re)*cos(im), sinh(re)*sin(im)) into the boxed out-slots.
	private static void emitComplexCoshInto(WasmLispCompiler.Ctx ctx, int reBox, int imBox, int reOut, int imOut) {
		int sinhRe = ctx.allocTemp();
		callSinhInto(ctx, reBox, sinhRe);
		int coshRe = ctx.allocTemp();
		callCoshInto(ctx, reBox, coshRe);
		int sinIm = ctx.allocTemp();
		callSinInto(ctx, imBox, sinIm);
		int cosIm = ctx.allocTemp();
		callCosInto(ctx, imBox, cosIm);
		WasmExpCompiler.unboxF64Local(ctx, coshRe);
		WasmExpCompiler.unboxF64Local(ctx, cosIm);
		ctx.writer.write(Instruction.F64_MUL);
		WasmExpCompiler.boxF64(ctx);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(reOut);
		WasmExpCompiler.unboxF64Local(ctx, sinhRe);
		WasmExpCompiler.unboxF64Local(ctx, sinIm);
		ctx.writer.write(Instruction.F64_MUL);
		WasmExpCompiler.boxF64(ctx);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(imOut);
	}

	// tanh(z) = sinh(z)/cosh(z) in f64 assembly.
	private static void emitComplexTanhInto(WasmLispCompiler.Ctx ctx, int reBox, int imBox, int reOut, int imOut) {
		int sRe = ctx.allocTemp();
		int sIm = ctx.allocTemp();
		emitComplexSinhInto(ctx, reBox, imBox, sRe, sIm);
		int cRe = ctx.allocTemp();
		int cIm = ctx.allocTemp();
		emitComplexCoshInto(ctx, reBox, imBox, cRe, cIm);
		emitComplexDivF64(ctx, sRe, sIm, cRe, cIm, reOut, imOut);
	}

	// (a+bi)/(c+di) over boxed f64 components into the boxed out-slots.
	private static void emitComplexDivF64(WasmLispCompiler.Ctx ctx, int aRe, int aIm, int cRe, int cIm, int reOut,
			int imOut) {
		WasmExpCompiler.unboxF64Local(ctx, cRe);
		WasmExpCompiler.unboxF64Local(ctx, cRe);
		ctx.writer.write(Instruction.F64_MUL);
		WasmExpCompiler.unboxF64Local(ctx, cIm);
		WasmExpCompiler.unboxF64Local(ctx, cIm);
		ctx.writer.write(Instruction.F64_MUL);
		ctx.writer.write(Instruction.F64_ADD);
		int denom = boxF64Temp(ctx);
		WasmExpCompiler.unboxF64Local(ctx, aRe);
		WasmExpCompiler.unboxF64Local(ctx, cRe);
		ctx.writer.write(Instruction.F64_MUL);
		WasmExpCompiler.unboxF64Local(ctx, aIm);
		WasmExpCompiler.unboxF64Local(ctx, cIm);
		ctx.writer.write(Instruction.F64_MUL);
		ctx.writer.write(Instruction.F64_ADD);
		WasmExpCompiler.unboxF64Local(ctx, denom);
		ctx.writer.write(Instruction.F64_DIV);
		WasmExpCompiler.boxF64(ctx);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(reOut);
		WasmExpCompiler.unboxF64Local(ctx, aIm);
		WasmExpCompiler.unboxF64Local(ctx, cRe);
		ctx.writer.write(Instruction.F64_MUL);
		WasmExpCompiler.unboxF64Local(ctx, aRe);
		WasmExpCompiler.unboxF64Local(ctx, cIm);
		ctx.writer.write(Instruction.F64_MUL);
		ctx.writer.write(Instruction.F64_SUB);
		WasmExpCompiler.unboxF64Local(ctx, denom);
		ctx.writer.write(Instruction.F64_DIV);
		WasmExpCompiler.boxF64(ctx);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(imOut);
	}

	// atan2(y, x) of the boxed components into the boxed out slot: the software
	// atan core plus quadrant assembly (NaN in, NaN out; the x == 0 rungs tell +0
	// from -0 through copysign, like Math.atan2).
	private static void emitAtan2Into(WasmLispCompiler.Ctx ctx, int yBox, int xBox, int out) {
		// NaN in either -> NaN.
		WasmExpCompiler.unboxF64Local(ctx, xBox);
		WasmExpCompiler.unboxF64Local(ctx, xBox);
		ctx.writer.write(Instruction.F64_NE);
		WasmExpCompiler.unboxF64Local(ctx, yBox);
		WasmExpCompiler.unboxF64Local(ctx, yBox);
		ctx.writer.write(Instruction.F64_NE);
		ctx.writer.write(Instruction.I32_OR);
		ctx.writer.write(Instruction.IF);
		ctx.writer.write(Type.F64);
		f64Const(ctx, Double.NaN);
		ctx.writer.write(Instruction.ELSE);
		// x > 0 -> atan(y/x).
		WasmExpCompiler.unboxF64Local(ctx, xBox);
		f64Const(ctx, 0.0);
		ctx.writer.write(Instruction.F64_GT);
		ctx.writer.write(Instruction.IF);
		ctx.writer.write(Type.F64);
		WasmExpCompiler.unboxF64Local(ctx, yBox);
		WasmExpCompiler.unboxF64Local(ctx, xBox);
		ctx.writer.write(Instruction.F64_DIV);
		callAtanInto(ctx);
		ctx.writer.write(Instruction.ELSE);
		// x < 0 -> atan(y/x) + copysign(pi, y).
		WasmExpCompiler.unboxF64Local(ctx, xBox);
		f64Const(ctx, 0.0);
		ctx.writer.write(Instruction.F64_LT);
		ctx.writer.write(Instruction.IF);
		ctx.writer.write(Type.F64);
		WasmExpCompiler.unboxF64Local(ctx, yBox);
		WasmExpCompiler.unboxF64Local(ctx, xBox);
		ctx.writer.write(Instruction.F64_DIV);
		callAtanInto(ctx);
		f64Const(ctx, Math.PI);
		WasmExpCompiler.unboxF64Local(ctx, yBox);
		ctx.writer.write(Instruction.F64_COPYSIGN);
		ctx.writer.write(Instruction.F64_ADD);
		ctx.writer.write(Instruction.ELSE);
		// x == 0: +0 -> y itself (signed zero preserved), -0 -> copysign(pi, y).
		f64Const(ctx, 1.0);
		WasmExpCompiler.unboxF64Local(ctx, xBox);
		ctx.writer.write(Instruction.F64_COPYSIGN);
		f64Const(ctx, 0.0);
		ctx.writer.write(Instruction.F64_GT);
		ctx.writer.write(Instruction.IF);
		ctx.writer.write(Type.F64);
		WasmExpCompiler.unboxF64Local(ctx, yBox);
		ctx.writer.write(Instruction.ELSE);
		f64Const(ctx, Math.PI);
		WasmExpCompiler.unboxF64Local(ctx, yBox);
		ctx.writer.write(Instruction.F64_COPYSIGN);
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.END);
		WasmExpCompiler.boxF64(ctx);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(out);
	}

	// sin of the boxed value into the boxed out slot (the real sin edges, then
	// the finite core).
	private static void callSinInto(WasmLispCompiler.Ctx ctx, int inBox, int outBox) {
		int xSlot = ctx.allocTemp();
		getLocal(ctx, inBox);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(xSlot);
		WasmExpCompiler.unboxF64Local(ctx, xSlot);
		WasmExpCompiler.unboxF64Local(ctx, xSlot);
		ctx.writer.write(Instruction.F64_NE);
		ctx.writer.write(Instruction.IF);
		ctx.writer.write(Type.F64);
		WasmExpCompiler.unboxF64Local(ctx, xSlot);
		ctx.writer.write(Instruction.ELSE);
		WasmExpCompiler.unboxF64Local(ctx, xSlot);
		ctx.writer.write(Instruction.F64_ABS);
		f64Const(ctx, Double.POSITIVE_INFINITY);
		ctx.writer.write(Instruction.F64_EQ);
		ctx.writer.write(Instruction.IF);
		ctx.writer.write(Type.F64);
		f64Const(ctx, Double.NaN);
		ctx.writer.write(Instruction.ELSE);
		WasmExpCompiler.unboxF64Local(ctx, xSlot);
		f64Const(ctx, 0.0);
		ctx.writer.write(Instruction.F64_EQ);
		ctx.writer.write(Instruction.IF);
		ctx.writer.write(Type.F64);
		WasmExpCompiler.unboxF64Local(ctx, xSlot);
		ctx.writer.write(Instruction.ELSE);
		WasmSinCosCompiler.emitSinMain(ctx, xSlot, ctx.allocTemp(), ctx.allocTemp(), ctx.allocTemp(), ctx.allocTemp());
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.END);
		WasmExpCompiler.boxF64(ctx);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(outBox);
	}

	// cos of the boxed value into the boxed out slot.
	private static void callCosInto(WasmLispCompiler.Ctx ctx, int inBox, int outBox) {
		int xSlot = ctx.allocTemp();
		getLocal(ctx, inBox);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(xSlot);
		WasmExpCompiler.unboxF64Local(ctx, xSlot);
		WasmExpCompiler.unboxF64Local(ctx, xSlot);
		ctx.writer.write(Instruction.F64_NE);
		ctx.writer.write(Instruction.IF);
		ctx.writer.write(Type.F64);
		WasmExpCompiler.unboxF64Local(ctx, xSlot);
		ctx.writer.write(Instruction.ELSE);
		WasmExpCompiler.unboxF64Local(ctx, xSlot);
		ctx.writer.write(Instruction.F64_ABS);
		f64Const(ctx, Double.POSITIVE_INFINITY);
		ctx.writer.write(Instruction.F64_EQ);
		ctx.writer.write(Instruction.IF);
		ctx.writer.write(Type.F64);
		f64Const(ctx, Double.NaN);
		ctx.writer.write(Instruction.ELSE);
		WasmSinCosCompiler.emitCosMain(ctx, xSlot, ctx.allocTemp(), ctx.allocTemp(), ctx.allocTemp(), ctx.allocTemp());
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.END);
		WasmExpCompiler.boxF64(ctx);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(outBox);
	}

	// sinh of the boxed value into the boxed out slot (the real sinh edges, then
	// the finite core).
	private static void callSinhInto(WasmLispCompiler.Ctx ctx, int inBox, int outBox) {
		int xSlot = ctx.allocTemp();
		getLocal(ctx, inBox);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(xSlot);
		WasmExpCompiler.unboxF64Local(ctx, xSlot);
		WasmExpCompiler.unboxF64Local(ctx, xSlot);
		ctx.writer.write(Instruction.F64_NE);
		ctx.writer.write(Instruction.IF);
		ctx.writer.write(Type.F64);
		WasmExpCompiler.unboxF64Local(ctx, xSlot);
		ctx.writer.write(Instruction.ELSE);
		WasmExpCompiler.unboxF64Local(ctx, xSlot);
		ctx.writer.write(Instruction.F64_ABS);
		f64Const(ctx, Double.POSITIVE_INFINITY);
		ctx.writer.write(Instruction.F64_EQ);
		ctx.writer.write(Instruction.IF);
		ctx.writer.write(Type.F64);
		WasmExpCompiler.unboxF64Local(ctx, xSlot);
		ctx.writer.write(Instruction.ELSE);
		WasmSinhCoshCompiler.emitSinhF64(ctx, xSlot, ctx.allocTemp(), ctx.allocTemp(), ctx.allocTemp());
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.END);
		WasmExpCompiler.boxF64(ctx);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(outBox);
	}

	// cosh of the boxed value into the boxed out slot.
	private static void callCoshInto(WasmLispCompiler.Ctx ctx, int inBox, int outBox) {
		int xSlot = ctx.allocTemp();
		getLocal(ctx, inBox);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(xSlot);
		WasmExpCompiler.unboxF64Local(ctx, xSlot);
		WasmExpCompiler.unboxF64Local(ctx, xSlot);
		ctx.writer.write(Instruction.F64_NE);
		ctx.writer.write(Instruction.IF);
		ctx.writer.write(Type.F64);
		WasmExpCompiler.unboxF64Local(ctx, xSlot);
		ctx.writer.write(Instruction.ELSE);
		WasmExpCompiler.unboxF64Local(ctx, xSlot);
		ctx.writer.write(Instruction.F64_ABS);
		f64Const(ctx, Double.POSITIVE_INFINITY);
		ctx.writer.write(Instruction.F64_EQ);
		ctx.writer.write(Instruction.IF);
		ctx.writer.write(Type.F64);
		f64Const(ctx, Double.POSITIVE_INFINITY);
		ctx.writer.write(Instruction.ELSE);
		WasmSinhCoshCompiler.emitCoshF64(ctx, xSlot, ctx.allocTemp(), ctx.allocTemp(), ctx.allocTemp());
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.END);
		WasmExpCompiler.boxF64(ctx);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(outBox);
	}

	// atan of the f64 on the stack, left on the stack.
	private static void callAtanInto(WasmLispCompiler.Ctx ctx) {
		WasmExpCompiler.boxF64(ctx);
		int xSlot = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(xSlot);
		WasmAtanCompiler.emitAtanCore(ctx, xSlot, ctx.allocTemp(), ctx.allocTemp(), ctx.allocTemp(), ctx.allocTemp());
	}

	private static int boxF64Temp(WasmLispCompiler.Ctx ctx) {
		int slot = ctx.allocTemp();
		WasmExpCompiler.boxF64(ctx);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(slot);
		return slot;
	}

	private static void getLocal(WasmLispCompiler.Ctx ctx, int slot) {
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(slot);
	}

	private static void call(WasmLispCompiler.Ctx ctx, int func) {
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeUnsignedLeb128(func);
	}

	private static void constI32(WasmLispCompiler.Ctx ctx, int value) {
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(value);
	}

	// Pushes the TYPE_COMPLEX tag (always zero; see WasmLispCompiler.TYPE_COMPLEX).
	private static void emitComplexTag(WasmLispCompiler.Ctx ctx) {
		constI32(ctx, 0);
	}

	private static void f64Const(WasmLispCompiler.Ctx ctx, double value) {
		ctx.writer.write(Instruction.F64_CONST);
		ctx.writer.writeF64(value);
	}

}
