import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/**
 * The per-call floor of an FFM downcall, by TARGET rather than by handle holding:
 * libcuda's cuDriverGetVersion(int*) against libc's abs(int) and getpid(), plain and
 * critical, so a libcuda-specific cost separates from the linker's own. A probe.
 */
public class DowncallFloor {

	static final int REPS = 2_000_000;

	static final ValueLayout.OfInt I = ValueLayout.JAVA_INT;

	static final Linker LINKER = Linker.nativeLinker();

	static final SymbolLookup CUDA = SymbolLookup.libraryLookup("libcuda.so.1", Arena.global());

	static final SymbolLookup LIBC = LINKER.defaultLookup();

	static final MethodHandle CU_VERSION = LINKER.downcallHandle(CUDA.find("cuDriverGetVersion").orElseThrow(),
			FunctionDescriptor.of(I, ValueLayout.ADDRESS));

	static final MethodHandle CU_VERSION_CRIT = LINKER.downcallHandle(CUDA.find("cuDriverGetVersion").orElseThrow(),
			FunctionDescriptor.of(I, ValueLayout.ADDRESS), Linker.Option.critical(true));

	static final MethodHandle ABS = LINKER.downcallHandle(LIBC.find("abs").orElseThrow(), FunctionDescriptor.of(I, I));

	static final MethodHandle ABS_CRIT = LINKER.downcallHandle(LIBC.find("abs").orElseThrow(),
			FunctionDescriptor.of(I, I), Linker.Option.critical(false));

	static final MethodHandle GETPID = LINKER.downcallHandle(LIBC.find("getpid").orElseThrow(),
			FunctionDescriptor.of(I));

	public static void main(String[] args) throws Throwable {
		String only = args.length > 0 ? args[0] : "all";
		int rounds = args.length > 1 ? Integer.parseInt(args[1]) : 5;
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment out = arena.allocate(I);
			for (int round = 0; round < rounds; round++) {
				StringBuilder line = new StringBuilder("round " + round);
				if (only.equals("all") || only.equals("cu"))
					line.append(String.format("  cuDriverGetVersion %8.1f", ns(() -> (int) CU_VERSION.invokeExact(out))));
				if (only.equals("all") || only.equals("cucrit"))
					line.append(String.format("  cu critical %8.1f", ns(() -> (int) CU_VERSION_CRIT.invokeExact(out))));
				if (only.equals("all") || only.equals("abs"))
					line.append(String.format("  abs %8.1f", ns(() -> (int) ABS.invokeExact(-7))));
				if (only.equals("all") || only.equals("abscrit"))
					line.append(String.format("  abs critical %8.1f", ns(() -> (int) ABS_CRIT.invokeExact(-7))));
				if (only.equals("all") || only.equals("getpid"))
					line.append(String.format("  getpid %8.1f", ns(() -> (int) GETPID.invokeExact())));
				if (only.equals("all") || only.equals("none"))
					line.append(String.format("  none %8.1f", ns(() -> out.get(I, 0))));
				System.out.println(line.append(" ns"));
			}
		}
	}

	interface Call {

		int run() throws Throwable;

	}

	static double ns(Call call) throws Throwable {
		int sink = 0;
		for (int i = 0; i < REPS / 10; i++) {
			sink += call.run();
		}
		long start = System.nanoTime();
		for (int i = 0; i < REPS; i++) {
			sink += call.run();
		}
		long elapsed = System.nanoTime() - start;
		if (sink == Integer.MIN_VALUE) {
			throw new IllegalStateException();
		}
		return elapsed / (double) REPS;
	}

}
