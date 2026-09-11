package am.ik.rontolisp.codegen.wasm;

import am.ik.wasm.Instruction;
import am.ik.wasm.Type;

/**
 * The real cores of the inverse hyperbolics for WASM -- software, because WASM has no
 * {@code log1p} and no {@code hypot} and the interpreter's grouping leans on both. Each
 * core takes an f64 on the stack and leaves an f64, over the domain the call site in
 * {@link WasmComplexCompiler} has already narrowed (the escape and the plane arms live
 * there, like the sqrt site's).
 *
 * <p>
 * The grouping differs from the interpreter's at the ulp level -- {@code asinh} takes the
 * interpreter's own one log over {@code |x| + sqrt(x^2 + 1)} but squares where it hypots,
 * {@code acosh} takes that same shape where the interpreter splits at 2 and uses glibc's
 * {@code log(2x)} middle branch, and {@code atanh} halves {@code log((1+x)/(1-x))} where
 * the interpreter differences two {@code log1p}s (the grouping behind SBCL's complex
 * path). WASM is the approximate backend these numbers are tested with {@code isCloseTo}
 * for; the exact anchors are the zero points, which each formula answers exactly (the log
 * core answers 0 at 1, and {@code sqrt(0) = 0}). The squaring runs only below 1e154,
 * where it cannot overflow; beyond, {@code log(a) + ln 2} stands in for {@code log(2a)}
 * within an ulp.
 */
final class WasmInverseHypCompiler {

	private WasmInverseHypCompiler() {
	}

	/**
	 * asinh of the f64 on the stack, leaving an f64: log(a + sqrt(a^2 + 1)) over |x| &lt;
	 * 1e154, log(a) + ln 2 beyond, with the operand's sign restored by copysign (odd, so
	 * -0.0 and the negative infinities keep their sign like the interpreter).
	 */
	static void emitAsinhRealF64(WasmLispCompiler.Ctx ctx) {
		int xSlot = ctx.allocTemp();
		WasmExpCompiler.boxF64(ctx);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(xSlot);
		unbox(ctx, xSlot);
		ctx.writer.write(Instruction.F64_ABS);
		int aSlot = ctx.allocTemp();
		boxInto(ctx, aSlot);
		unbox(ctx, aSlot);
		f64Const(ctx, 1e154);
		ctx.writer.write(Instruction.F64_LT);
		ctx.writer.write(Instruction.IF);
		ctx.writer.write(Type.F64);
		unbox(ctx, aSlot);
		unbox(ctx, aSlot);
		unbox(ctx, aSlot);
		ctx.writer.write(Instruction.F64_MUL);
		f64Const(ctx, 1.0);
		ctx.writer.write(Instruction.F64_ADD);
		ctx.writer.write(Instruction.F64_SQRT);
		ctx.writer.write(Instruction.F64_ADD);
		logCoreUnboxed(ctx);
		ctx.writer.write(Instruction.ELSE);
		unbox(ctx, aSlot);
		logCoreUnboxed(ctx);
		f64Const(ctx, 0.6931471805599453);
		ctx.writer.write(Instruction.F64_ADD);
		ctx.writer.write(Instruction.END);
		unbox(ctx, xSlot);
		ctx.writer.write(Instruction.F64_COPYSIGN);
	}

	/**
	 * acosh of the f64 on the stack (the site has proven x &gt;= 1 or NaN), leaving an
	 * f64: log(x + sqrt(x^2 - 1)) below 1e154 -- the addition cancels nothing, so the
	 * near-1 region is accurate without a log1p -- and log(x) + ln 2 beyond. Exactly 0 at
	 * 1 (the log core answers 0 at 1).
	 */
	static void emitAcoshRealF64(WasmLispCompiler.Ctx ctx) {
		int xSlot = ctx.allocTemp();
		WasmExpCompiler.boxF64(ctx);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(xSlot);
		unbox(ctx, xSlot);
		f64Const(ctx, 1e154);
		ctx.writer.write(Instruction.F64_LT);
		ctx.writer.write(Instruction.IF);
		ctx.writer.write(Type.F64);
		unbox(ctx, xSlot);
		unbox(ctx, xSlot);
		unbox(ctx, xSlot);
		ctx.writer.write(Instruction.F64_MUL);
		f64Const(ctx, 1.0);
		ctx.writer.write(Instruction.F64_SUB);
		ctx.writer.write(Instruction.F64_SQRT);
		ctx.writer.write(Instruction.F64_ADD);
		logCoreUnboxed(ctx);
		ctx.writer.write(Instruction.ELSE);
		unbox(ctx, xSlot);
		logCoreUnboxed(ctx);
		f64Const(ctx, 0.6931471805599453);
		ctx.writer.write(Instruction.F64_ADD);
		ctx.writer.write(Instruction.END);
	}

	/**
	 * atanh of the f64 on the stack (the site has proven |x| &lt;= 1 or NaN), leaving an
	 * f64: half of log((1+x)/(1-x)) -- exactly 0 at 0. x = +-1 divides to +-inf and the
	 * log answers +-inf, like the interpreter's log1p form.
	 */
	static void emitAtanhRealF64(WasmLispCompiler.Ctx ctx) {
		int xSlot = ctx.allocTemp();
		WasmExpCompiler.boxF64(ctx);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(xSlot);
		unbox(ctx, xSlot);
		f64Const(ctx, 1.0);
		ctx.writer.write(Instruction.F64_ADD);
		f64Const(ctx, 1.0);
		unbox(ctx, xSlot);
		ctx.writer.write(Instruction.F64_SUB);
		ctx.writer.write(Instruction.F64_DIV);
		logCoreUnboxed(ctx);
		f64Const(ctx, 0.5);
		ctx.writer.write(Instruction.F64_MUL);
	}

	// ln of the f64 on the stack, left unboxed: the log core answers a boxed float,
	// so it parks in a temp and comes back through the unbox.
	private static void logCoreUnboxed(WasmLispCompiler.Ctx ctx) {
		WasmLogCompiler.emitLogCore(ctx, ctx.allocTemp(), ctx.allocTemp(), ctx.allocTemp());
		int lSlot = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(lSlot);
		unbox(ctx, lSlot);
	}

	private static void unbox(WasmLispCompiler.Ctx ctx, int slot) {
		WasmExpCompiler.unboxF64Local(ctx, slot);
	}

	// Boxes the f64 on the stack and stores it into the given slot.
	private static void boxInto(WasmLispCompiler.Ctx ctx, int slot) {
		WasmExpCompiler.boxF64(ctx);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(slot);
	}

	private static void f64Const(WasmLispCompiler.Ctx ctx, double value) {
		ctx.writer.write(Instruction.F64_CONST);
		ctx.writer.writeF64(value);
	}

}
