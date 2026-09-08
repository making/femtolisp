package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;

/**
 * Compiles the {@code exp} built-in for WASM. WASM has no native transcendental
 * instruction, so this emits a software approximation of {@code e^x} entirely in f64
 * arithmetic, always returning a float.
 *
 * <p>
 * The approximation uses the standard range reduction: {@code x = k*ln2 + r} with
 * {@code k = nearest(x / ln2)} and {@code |r| <= ln2/2} (a two-part ln2 split, like the
 * Cody-Waite reduction {@link WasmSinCosCompiler} does for pi/2), {@code e^r} by a
 * degree-12 Taylor polynomial evaluated in Horner form, then a scale by {@code 2^k}
 * through the exponent bits (the trick {@link WasmLogCompiler} uses to take the exponent
 * OUT). The edges match {@code Math.exp}: {@code NaN -> NaN}, {@code x > 709.8 ->
 * +inf}, {@code x < -745.2 -> 0.0} (below the smallest denormal, so the answer is exactly
 * zero).
 *
 * <p>
 * Accuracy is a few dozen ulps (~3e-14 relative, measured over the full finite range;
 * near overflow the reduction's absolute error grows like {@code |x| * 2^-53}, and in the
 * denormal zone the last ulp is a larger relative step -- both inherent to the format,
 * not the polynomial). It is close to but not bit-identical to the interpreter/JVM
 * {@code Math.exp}, so cross-backend output for {@code exp} of a non-trivial argument
 * differs in the low-order digits. Exact anchors: {@code (exp 0)} and {@code (exp -0.0)}
 * are exactly {@code 1.0}.
 *
 * <p>
 * Intermediate f64 values are boxed as {@code TYPE_FLOAT} structs in ref-typed
 * temporaries because the WASM compiler only allocates {@code (ref null eq)} locals; this
 * keeps the implementation inline, with no new function or type indices.
 */
final class WasmExpCompiler {

	// All constants package-private so the --simd unary kernels
	// (WasmVecSimdRuntimeBuilder.emitExpF64) reproduce the SAME approximation on raw
	// f64 locals -- bit-identity to this defun path by shared constants and operation
	// order.

	/** 1/ln(2): the range index is {@code k = nearest(x * INV_LN2)}. */
	static final double INV_LN2 = 1.4426950408889634;

	/**
	 * ln(2) with its low 12 mantissa bits cleared, so {@code k * LN2_HI} is exact for
	 * {@code |k| < 2048} (the edge guards keep {@code |k| <= 1024}).
	 */
	static final double LN2_HI = Double.longBitsToDouble(0x3FE62E42FEFA3000L);

	/**
	 * {@code ln(2) - LN2_HI}, exact by Sterbenz: {@code LN2_HI + LN2_LO} is ln(2) to full
	 * f64 precision.
	 */
	static final double LN2_LO = 2.823297151621773e-13;

	/**
	 * Above this bound the result overflows to {@code +inf} (the JVM overflows just below
	 * it, at {@code ln(Double.MAX_VALUE)}; the 1-2 ulp band between answers {@code +inf}
	 * here, the overflow cliff both libms share).
	 */
	static final double OVERFLOW_HI = 709.8;

	/**
	 * Below this bound the result underflows past the smallest denormal, so the answer is
	 * exactly {@code 0.0}. This is what lets a {@code -infinity} attention mask reach
	 * {@code linalg:softmax} as a weight of exactly {@code 0.0} on every backend.
	 */
	static final double UNDERFLOW_LO = -745.2;

	/** 2^-54, the second scale step of the denormal path (see {@link #emitScale}). */
	static final double TWO_POW_NEG54 = 0x1p-54;

	/**
	 * 2^1023, the first scale step of the near-overflow path (see {@link #emitScale}).
	 */
	static final double TWO_POW_1023 = 0x1p1023;

	// Taylor coefficients of exp around 0, from the highest degree down (Horner order):
	// 1/12!, 1/11!, ..., 1/2!, 1, 1.
	static final double[] HORNER_COEFFS = { 1.0 / 479001600.0, 1.0 / 39916800.0, 1.0 / 3628800.0, 1.0 / 362880.0,
			1.0 / 40320.0, 1.0 / 5040.0, 1.0 / 720.0, 1.0 / 120.0, 1.0 / 24.0, 1.0 / 6.0, 0.5, 1.0, 1.0 };

	private WasmExpCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		if (args.size() != 2) {
			throw new UnsupportedOperationException("exp expects 1 argument, got " + (args.size() - 1));
		}
		int xSlot = ctx.allocTemp();
		int kSlot = ctx.allocTemp();
		int accSlot = ctx.allocTemp();
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		WasmEmitHelper.castFloatGetF64(ctx);
		emitExpCore(ctx, xSlot, kSlot, accSlot);
	}

	/**
	 * Consumes an f64 {@code x} on the stack and leaves the boxed {@code TYPE_FLOAT}
	 * {@code exp(x)}, the boxed copy in {@code accSlot}. Package-private so
	 * {@link WasmTanhCompiler} derives {@code tanh} from the same approximation (the same
	 * arithmetic order the {@code --simd} kernels mirror on raw f64 locals via
	 * {@code WasmVecSimdRuntimeBuilder.emitExpF64}).
	 */
	static void emitExpCore(WasmLispCompiler.Ctx ctx, int xSlot, int kSlot, int accSlot) {
		// Box x; everything below works on the boxed temps.
		boxF64(ctx);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(xSlot);

		// The IEEE edges, then the finite main path.
		// if (x != x) -> NaN (x itself)
		unboxF64Local(ctx, xSlot);
		unboxF64Local(ctx, xSlot);
		ctx.writer.write(Instruction.F64_NE);
		ctx.writer.write(Instruction.IF);
		ctx.writer.write(Type.F64);
		unboxF64Local(ctx, xSlot);
		ctx.writer.write(Instruction.ELSE);
		// if (x > OVERFLOW_HI) -> +inf
		unboxF64Local(ctx, xSlot);
		ctx.writer.write(Instruction.F64_CONST);
		ctx.writer.writeF64(OVERFLOW_HI);
		ctx.writer.write(Instruction.F64_GT);
		ctx.writer.write(Instruction.IF);
		ctx.writer.write(Type.F64);
		ctx.writer.write(Instruction.F64_CONST);
		ctx.writer.writeF64(Double.POSITIVE_INFINITY);
		ctx.writer.write(Instruction.ELSE);
		// if (x < UNDERFLOW_LO) -> 0.0
		unboxF64Local(ctx, xSlot);
		ctx.writer.write(Instruction.F64_CONST);
		ctx.writer.writeF64(UNDERFLOW_LO);
		ctx.writer.write(Instruction.F64_LT);
		ctx.writer.write(Instruction.IF);
		ctx.writer.write(Type.F64);
		ctx.writer.write(Instruction.F64_CONST);
		ctx.writer.writeF64(0.0);
		ctx.writer.write(Instruction.ELSE);
		emitMain(ctx, xSlot, kSlot, accSlot);
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.END);
		// The result is the boxed TYPE_FLOAT in accSlot.
		boxF64(ctx);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(accSlot);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(accSlot);
	}

	// The finite path: x = k*ln2 + r, e^r by the Taylor polynomial, scaled by 2^k.
	// Leaves the f64 result on the stack and the boxed e^r midpoint in accSlot.
	private static void emitMain(WasmLispCompiler.Ctx ctx, int xSlot, int kSlot, int accSlot) {
		// k = nearest(x * INV_LN2), boxed into kSlot.
		unboxF64Local(ctx, xSlot);
		ctx.writer.write(Instruction.F64_CONST);
		ctx.writer.writeF64(INV_LN2);
		ctx.writer.write(Instruction.F64_MUL);
		ctx.writer.write(Instruction.F64_NEAREST);
		boxF64(ctx);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(kSlot);

		// r = (x - k*LN2_HI) - k*LN2_LO, boxed back into xSlot.
		unboxF64Local(ctx, xSlot);
		unboxF64Local(ctx, kSlot);
		ctx.writer.write(Instruction.F64_CONST);
		ctx.writer.writeF64(LN2_HI);
		ctx.writer.write(Instruction.F64_MUL);
		ctx.writer.write(Instruction.F64_SUB);
		unboxF64Local(ctx, kSlot);
		ctx.writer.write(Instruction.F64_CONST);
		ctx.writer.writeF64(LN2_LO);
		ctx.writer.write(Instruction.F64_MUL);
		ctx.writer.write(Instruction.F64_SUB);
		boxF64(ctx);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(xSlot);

		// Horner evaluation of the Taylor polynomial, boxed into accSlot.
		ctx.writer.write(Instruction.F64_CONST);
		ctx.writer.writeF64(HORNER_COEFFS[0]);
		for (int i = 1; i < HORNER_COEFFS.length; i++) {
			unboxF64Local(ctx, xSlot);
			ctx.writer.write(Instruction.F64_MUL);
			ctx.writer.write(Instruction.F64_CONST);
			ctx.writer.writeF64(HORNER_COEFFS[i]);
			ctx.writer.write(Instruction.F64_ADD);
		}
		boxF64(ctx);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(accSlot);

		emitScale(ctx, kSlot, accSlot);
	}

	// Multiplies the boxed e^r in accSlot by 2^k (k boxed in kSlot); leaves the f64
	// result on the stack. Three cases: k == 1024 (only reachable just below the
	// overflow edge) scales as (e^r * 2^1023) * 2, since the single 2^1024 scale is
	// +inf while e^x itself may still be finite; k >= -1021 scales through the
	// exponent bits directly; below that (denormal results) (e^r * 2^(k+54)) * 2^-54
	// keeps the first scale in the normal exponent range, and scaling a normal by an
	// exact power of two rounds the denormal result correctly.
	private static void emitScale(WasmLispCompiler.Ctx ctx, int kSlot, int accSlot) {
		// if (trunc(k) == 1024)
		unboxF64Local(ctx, kSlot);
		ctx.writer.write(Instruction.I32_TRUNC_S_F64);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(1024);
		ctx.writer.write(Instruction.I32_EQ);
		ctx.writer.write(Instruction.IF);
		ctx.writer.write(Type.F64);
		unboxF64Local(ctx, accSlot);
		ctx.writer.write(Instruction.F64_CONST);
		ctx.writer.writeF64(TWO_POW_1023);
		ctx.writer.write(Instruction.F64_MUL);
		ctx.writer.write(Instruction.F64_CONST);
		ctx.writer.writeF64(2.0);
		ctx.writer.write(Instruction.F64_MUL);
		ctx.writer.write(Instruction.ELSE);
		// if (trunc(k) >= -1021)
		unboxF64Local(ctx, kSlot);
		ctx.writer.write(Instruction.I32_TRUNC_S_F64);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(-1021);
		ctx.writer.write(Instruction.I32_GE_S);
		ctx.writer.write(Instruction.IF);
		ctx.writer.write(Type.F64);
		// e^r * reinterpret(((i64)k + 1023) << 52)
		unboxF64Local(ctx, accSlot);
		unboxF64Local(ctx, kSlot);
		ctx.writer.write(Instruction.I32_TRUNC_S_F64);
		ctx.writer.write(Instruction.I64_EXTEND_S_I32);
		ctx.writer.write(Instruction.I64_CONST);
		ctx.writer.writeSignedLeb128(1023);
		ctx.writer.write(Instruction.I64_ADD);
		ctx.writer.write(Instruction.I64_CONST);
		ctx.writer.writeSignedLeb128(52);
		ctx.writer.write(Instruction.I64_SHL);
		ctx.writer.write(Instruction.F64_REINTERPRET_I64);
		ctx.writer.write(Instruction.F64_MUL);
		ctx.writer.write(Instruction.ELSE);
		// (e^r * reinterpret(((i64)k + 1077) << 52)) * 2^-54
		unboxF64Local(ctx, accSlot);
		unboxF64Local(ctx, kSlot);
		ctx.writer.write(Instruction.I32_TRUNC_S_F64);
		ctx.writer.write(Instruction.I64_EXTEND_S_I32);
		ctx.writer.write(Instruction.I64_CONST);
		ctx.writer.writeSignedLeb128(1077);
		ctx.writer.write(Instruction.I64_ADD);
		ctx.writer.write(Instruction.I64_CONST);
		ctx.writer.writeSignedLeb128(52);
		ctx.writer.write(Instruction.I64_SHL);
		ctx.writer.write(Instruction.F64_REINTERPRET_I64);
		ctx.writer.write(Instruction.F64_MUL);
		ctx.writer.write(Instruction.F64_CONST);
		ctx.writer.writeF64(TWO_POW_NEG54);
		ctx.writer.write(Instruction.F64_MUL);
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.END);
	}

	// Boxes the f64 on the stack into a TYPE_FLOAT struct. Package-private for
	// WasmTanhCompiler / WasmLogCompiler, which build on the same boxed-temp idiom.
	static void boxF64(WasmLispCompiler.Ctx ctx) {
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_FLOAT);
	}

	// Loads local[slot] (a TYPE_FLOAT struct) and extracts its f64 field onto the stack.
	// Package-private for WasmTanhCompiler / WasmLogCompiler.
	static void unboxF64Local(WasmLispCompiler.Ctx ctx, int slot) {
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(slot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_FLOAT);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_FLOAT);
		ctx.writer.writeUnsignedLeb128(0);
	}

}
