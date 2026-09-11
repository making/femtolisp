package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;

/**
 * Compiles the {@code %make-directories} internal primitive: the T symbol when the
 * directory exists afterwards, a Lisp error otherwise. The path argument is compiled to a
 * runtime string and passed to the {@code _make_directories} runtime, which creates every
 * missing level through the {@code path_create_directory} import. The null check + signal
 * live HERE rather than in the runtime helper -- the {@code _open} precedent -- so the
 * error is a catchable {@code throw} in EH mode (and the same trap as before outside it).
 */
final class WasmMakeDirectoriesCompiler {

	private WasmMakeDirectoriesCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> parts = cons.toList();
		if (parts.size() != 2) {
			throw new UnsupportedOperationException(
					LispNames.MAKE_DIRECTORIES + " expects 1 argument, got " + (parts.size() - 1));
		}
		WasmExprCompiler.compileExpr(parts.get(1), ctx);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_MAKE_DIRECTORIES);
		int result = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(result);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(result);
		ctx.writer.write(Instruction.REF_IS_NULL);
		ctx.writer.write(Instruction.IF, 0x40);
		// Stack-polymorphic in both modes (unreachable / throw), so the void block type
		// is correct either way.
		WasmErrorCompiler.compile(new LispCons(new LispSymbol(LispNames.ERROR_INTERNAL), new LispCons(
				new LispString("%make-directories: cannot create directory"), am.ik.rontolisp.LispNil.INSTANCE)), ctx);
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(result);
	}

}
