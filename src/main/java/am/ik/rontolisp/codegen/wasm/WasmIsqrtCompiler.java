package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;

/**
 * Compiles the {@code isqrt} built-in: the integer square root (floor of the real square
 * root). Computed as {@code trunc(floor(sqrt((f64) x)))}; for the i31 integer range the
 * f64 result is exact, so no integer-domain correction is needed. A complex operand takes
 * the integer-funnel exit first (isqrt is integer-only, like the interpreter's
 * asBigInteger funnel and the JVM backend).
 */
final class WasmIsqrtCompiler {

	private WasmIsqrtCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		int slot = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(slot);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(slot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_COMPLEX);
		ctx.writer.write(Instruction.IF, 0x40);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(slot);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_TYPE_ERR_INT);
		ctx.writer.write(Instruction.UNREACHABLE);
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(slot);
		WasmEmitHelper.castFloatGetF64(ctx);
		ctx.writer.write(Instruction.F64_SQRT);
		ctx.writer.write(Instruction.F64_FLOOR);
		ctx.writer.write(Instruction.I32_TRUNC_S_F64);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
	}

}
