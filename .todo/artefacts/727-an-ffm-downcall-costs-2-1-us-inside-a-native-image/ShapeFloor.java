import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/**
 * The per-call floor of the downcall SHAPES rontolisp's native binary actually issues,
 * each called so that the callee returns at once: cuCtxSetCurrent(NULL) (1 arg),
 * cuMemcpyHtoD_v2 with a null pointer (3), cuLaunchKernel with a null function (11),
 * cblas_dgemv (12) and cblas_dgemm (14) at zero extent, plain and critical. What is
 * timed is the invoker and the stub, not the callee. A probe.
 */
public class ShapeFloor {

	static final int REPS = 1_000_000;

	static final ValueLayout.OfInt I = ValueLayout.JAVA_INT;

	static final ValueLayout.OfLong L = ValueLayout.JAVA_LONG;

	static final ValueLayout.OfDouble D = ValueLayout.JAVA_DOUBLE;

	static final ValueLayout A = ValueLayout.ADDRESS;

	static final Linker LINKER = Linker.nativeLinker();

	static final SymbolLookup CUDA = SymbolLookup.libraryLookup("libcuda.so.1", Arena.global());

	static final SymbolLookup BLAS = SymbolLookup.libraryLookup("libopenblas.so.0", Arena.global());

	static final FunctionDescriptor SET_CURRENT = FunctionDescriptor.of(I, A);

	static final FunctionDescriptor HTOD = FunctionDescriptor.of(I, L, A, L);

	static final FunctionDescriptor LAUNCH = FunctionDescriptor.of(I, A, I, I, I, I, I, I, I, A, A, A);

	static final FunctionDescriptor GEMV = FunctionDescriptor.ofVoid(I, I, I, I, D, A, I, A, I, D, A, I);

	static final FunctionDescriptor GEMM = FunctionDescriptor.ofVoid(I, I, I, I, I, I, D, A, I, A, I, D, A, I);

	static MethodHandle bind(SymbolLookup in, String name, FunctionDescriptor shape, Linker.Option... options) {
		return LINKER.downcallHandle(in.find(name).orElseThrow(), shape, options);
	}

	static final MethodHandle CTX_SET_CURRENT = bind(CUDA, "cuCtxSetCurrent", SET_CURRENT);

	static final MethodHandle MEMCPY_HTOD = bind(CUDA, "cuMemcpyHtoD_v2", HTOD);

	static final MethodHandle LAUNCH_KERNEL = bind(CUDA, "cuLaunchKernel", LAUNCH);

	static final MethodHandle DGEMV = bind(BLAS, "cblas_dgemv", GEMV, Linker.Option.critical(true));

	static final MethodHandle DGEMM = bind(BLAS, "cblas_dgemm", GEMM, Linker.Option.critical(true));

	static final MethodHandle DGEMM_PLAIN = bind(BLAS, "cblas_dgemm", GEMM);

	public static void main(String[] args) throws Throwable {
		MemorySegment nul = MemorySegment.NULL;
		double[] eight = new double[8];
		MemorySegment heap = MemorySegment.ofArray(eight);
		for (int round = 0; round < 4; round++) {
			double a = ns(() -> (int) CTX_SET_CURRENT.invokeExact(nul));
			double b = ns(() -> (int) MEMCPY_HTOD.invokeExact(0L, nul, 0L));
			double c = ns(() -> (int) LAUNCH_KERNEL.invokeExact(nul, 1, 1, 1, 1, 1, 1, 0, nul, nul, nul));
			double d = ns(() -> {
				DGEMV.invokeExact(101, 111, 0, 0, 1.0, heap, 1, heap, 1, 0.0, heap, 1);
				return 0;
			});
			double e = ns(() -> {
				DGEMM.invokeExact(101, 111, 111, 0, 0, 0, 1.0, heap, 1, heap, 1, 0.0, heap, 1);
				return 0;
			});
			double f = ns(() -> {
				DGEMM_PLAIN.invokeExact(101, 111, 111, 0, 0, 0, 1.0, nul, 1, nul, 1, 0.0, nul, 1);
				return 0;
			});
			System.out.printf(
					"round %d  cuCtxSetCurrent(1) %7.1f  cuMemcpyHtoD(3) %7.1f  cuLaunchKernel(11) %7.1f"
							+ "  dgemv critical(12) %7.1f  dgemm critical(14) %7.1f  dgemm plain(14) %7.1f ns%n",
					round, a, b, c, d, e, f);
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
