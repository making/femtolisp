import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

import org.graalvm.nativeimage.PinnedObject;
import org.graalvm.nativeimage.c.function.CFunction;
import org.graalvm.nativeimage.c.function.CFunctionPointer;
import org.graalvm.nativeimage.c.function.InvokeCFunctionPointer;
import org.graalvm.nativeimage.c.type.CDoublePointer;
import org.graalvm.word.PointerBase;
import org.graalvm.word.WordFactory;

/**
 * The six downcall SHAPES rontolisp's native binary issues (ShapeFloor.java), each
 * through the FFM handle AND through SubstrateVM's own {@code @InvokeCFunctionPointer}
 * route to the SAME address, and for the two BLAS shapes the heap-array operands pinned
 * per call ({@code PinnedObject}) the way a substituted {@code LinalgBlasKernels.gemv}
 * would have to. Native-image only. A probe, not project code.
 */
public class CfpShapeFloor {

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

	static final MemorySegment CTX_SET_CURRENT_ADDR = CUDA.find("cuCtxSetCurrent").orElseThrow();

	static final MemorySegment MEMCPY_HTOD_ADDR = CUDA.find("cuMemcpyHtoD_v2").orElseThrow();

	static final MemorySegment LAUNCH_KERNEL_ADDR = CUDA.find("cuLaunchKernel").orElseThrow();

	static final MemorySegment DGEMV_ADDR = BLAS.find("cblas_dgemv").orElseThrow();

	static final MemorySegment DGEMM_ADDR = BLAS.find("cblas_dgemm").orElseThrow();

	static final MethodHandle CTX_SET_CURRENT = LINKER.downcallHandle(CTX_SET_CURRENT_ADDR, SET_CURRENT);

	static final MethodHandle MEMCPY_HTOD = LINKER.downcallHandle(MEMCPY_HTOD_ADDR, HTOD);

	static final MethodHandle LAUNCH_KERNEL = LINKER.downcallHandle(LAUNCH_KERNEL_ADDR, LAUNCH);

	static final MethodHandle DGEMV = LINKER.downcallHandle(DGEMV_ADDR, GEMV, Linker.Option.critical(true));

	static final MethodHandle DGEMM = LINKER.downcallHandle(DGEMM_ADDR, GEMM, Linker.Option.critical(true));

	interface CtxSetCurrent extends CFunctionPointer {

		@InvokeCFunctionPointer
		int call(PointerBase context);

	}

	interface MemcpyHtoD extends CFunctionPointer {

		@InvokeCFunctionPointer
		int call(long destination, PointerBase source, long bytes);

	}

	interface LaunchKernel extends CFunctionPointer {

		@InvokeCFunctionPointer
		int call(PointerBase function, int gridX, int gridY, int gridZ, int blockX, int blockY, int blockZ,
				int sharedBytes, PointerBase stream, PointerBase parameters, PointerBase extra);

	}

	interface Dgemv extends CFunctionPointer {

		@InvokeCFunctionPointer
		void call(int order, int trans, int rows, int cols, double alpha, CDoublePointer a, int lda, CDoublePointer x,
				int incx, double beta, CDoublePointer y, int incy);

		@InvokeCFunctionPointer(transition = CFunction.Transition.NO_TRANSITION)
		void callNoTransition(int order, int trans, int rows, int cols, double alpha, CDoublePointer a, int lda,
				CDoublePointer x, int incx, double beta, CDoublePointer y, int incy);

	}

	interface Dgemm extends CFunctionPointer {

		@InvokeCFunctionPointer
		void call(int order, int transA, int transB, int m, int n, int k, double alpha, CDoublePointer a, int lda,
				CDoublePointer b, int ldb, double beta, CDoublePointer c, int ldc);

	}



	static double[] a, x, y;

	static int n;

	static int cfpCtxSetCurrent() {
		CtxSetCurrent fp = WordFactory.pointer(CTX_SET_CURRENT_ADDR.address());
		return fp.call(WordFactory.nullPointer());
	}

	static int cfpMemcpyHtoD() {
		MemcpyHtoD fp = WordFactory.pointer(MEMCPY_HTOD_ADDR.address());
		return fp.call(0L, WordFactory.nullPointer(), 0L);
	}

	static int cfpLaunchKernel() {
		LaunchKernel fp = WordFactory.pointer(LAUNCH_KERNEL_ADDR.address());
		return fp.call(WordFactory.nullPointer(), 1, 1, 1, 1, 1, 1, 0, WordFactory.nullPointer(), WordFactory.nullPointer(), WordFactory.nullPointer());
	}

	/** Zero extent, pinned operands: the floor of a substituted gemv. */
	static int cfpDgemvPinned(int rows, int cols) {
		Dgemv fp = WordFactory.pointer(DGEMV_ADDR.address());
		try (PinnedObject pa = PinnedObject.create(a);
				PinnedObject px = PinnedObject.create(x);
				PinnedObject py = PinnedObject.create(y)) {
			fp.call(101, 111, rows, cols, 1.0, pa.addressOfArrayElement(0), Math.max(cols, 1),
					px.addressOfArrayElement(0), 1, 0.0, py.addressOfArrayElement(0), 1);
		}
		return 0;
	}

	static int cfpDgemvPinnedNoTransition(int rows, int cols) {
		Dgemv fp = WordFactory.pointer(DGEMV_ADDR.address());
		try (PinnedObject pa = PinnedObject.create(a);
				PinnedObject px = PinnedObject.create(x);
				PinnedObject py = PinnedObject.create(y)) {
			fp.callNoTransition(101, 111, rows, cols, 1.0, pa.addressOfArrayElement(0), Math.max(cols, 1),
					px.addressOfArrayElement(0), 1, 0.0, py.addressOfArrayElement(0), 1);
		}
		return 0;
	}

	static int cfpDgemmPinned() {
		Dgemm fp = WordFactory.pointer(DGEMM_ADDR.address());
		try (PinnedObject pa = PinnedObject.create(a);
				PinnedObject px = PinnedObject.create(x);
				PinnedObject py = PinnedObject.create(y)) {
			fp.call(101, 111, 111, 0, 0, 0, 1.0, pa.addressOfArrayElement(0), 1, px.addressOfArrayElement(0), 1, 0.0,
					py.addressOfArrayElement(0), 1);
		}
		return 0;
	}

	static int cfpDgemvPinnedZero() {
		return cfpDgemvPinned(0, 0);
	}

	static int cfpDgemvPinnedNoTransitionZero() {
		return cfpDgemvPinnedNoTransition(0, 0);
	}

	static int cfpDgemvPinnedReal() {
		return cfpDgemvPinned(n, n);
	}

	static int cfpDgemvPinnedNoTransitionReal() {
		return cfpDgemvPinnedNoTransition(n, n);
	}

	static int ffmDgemvReal() throws Throwable {
		return ffmDgemv(n, n);
	}

	static int ffmDgemv(int rows, int cols) throws Throwable {
		MemorySegment ha = MemorySegment.ofArray(a), hx = MemorySegment.ofArray(x), hy = MemorySegment.ofArray(y);
		DGEMV.invokeExact(101, 111, rows, cols, 1.0, ha, Math.max(cols, 1), hx, 1, 0.0, hy, 1);
		return 0;
	}

	public static void main(String[] args) throws Throwable {
		MemorySegment nul = MemorySegment.NULL;
		int size = args.length > 0 ? Integer.parseInt(args[0]) : 256;
		n = size;
		a = new double[size * size];
		x = new double[size];
		y = new double[size];
		java.util.Arrays.fill(a, 0.5);
		java.util.Arrays.fill(x, 0.25);
		double[] eight = new double[8];
		MemorySegment heap = MemorySegment.ofArray(eight);
		System.out.println("floors (callee returns at once), ns per call, FFM handle against @InvokeCFunctionPointer");
		for (int round = 0; round < 3; round++) {
			double a1 = ns(() -> (int) CTX_SET_CURRENT.invokeExact(nul));
			double a2 = ns(CfpShapeFloor::cfpCtxSetCurrent);
			double b1 = ns(() -> (int) MEMCPY_HTOD.invokeExact(0L, nul, 0L));
			double b2 = ns(CfpShapeFloor::cfpMemcpyHtoD);
			double c1 = ns(() -> (int) LAUNCH_KERNEL.invokeExact(nul, 1, 1, 1, 1, 1, 1, 0, nul, nul, nul));
			double c2 = ns(CfpShapeFloor::cfpLaunchKernel);
			double d1 = ns(() -> {
				DGEMV.invokeExact(101, 111, 0, 0, 1.0, heap, 1, heap, 1, 0.0, heap, 1);
				return 0;
			});
			double d2 = ns(CfpShapeFloor::cfpDgemvPinnedZero);
			double d3 = ns(CfpShapeFloor::cfpDgemvPinnedNoTransitionZero);
			double e1 = ns(() -> {
				DGEMM.invokeExact(101, 111, 111, 0, 0, 0, 1.0, heap, 1, heap, 1, 0.0, heap, 1);
				return 0;
			});
			double e2 = ns(CfpShapeFloor::cfpDgemmPinned);
			System.out.printf("round %d  cuCtxSetCurrent(1) %7.1f / %5.1f   cuMemcpyHtoD(3) %7.1f / %5.1f"
					+ "   cuLaunchKernel(11) %7.1f / %5.1f   dgemv(12, 3 pins) %7.1f / %5.1f (no-transition %5.1f)"
					+ "   dgemm(14, 3 pins) %7.1f / %5.1f%n", round, a1, a2, b1, b2, c1, c2, d1, d2, d3, e1, e2);
		}
		System.out.println("a real dgemv, " + size + "x" + size + " by " + size + ", ns per call (OpenBLAS at "
				+ System.getenv("OPENBLAS_NUM_THREADS") + " threads)");
		for (int round = 0; round < 3; round++) {
			double f1 = nsFew(CfpShapeFloor::ffmDgemvReal);
			double f2 = nsFew(CfpShapeFloor::cfpDgemvPinnedReal);
			double f3 = nsFew(CfpShapeFloor::cfpDgemvPinnedNoTransitionReal);
			System.out.printf("round %d  ffm critical %9.1f   cfp pinned %9.1f   cfp pinned no-transition %9.1f%n",
					round, f1, f2, f3);
		}
	}

	interface Call {

		int run() throws Throwable;

	}

	static double ns(Call call) throws Throwable {
		return ns(call, REPS);
	}

	static double nsFew(Call call) throws Throwable {
		return ns(call, (int) Math.max(1000, Math.min(REPS, 2_000_000_000L / ((long) n * n + 1))));
	}

	static double ns(Call call, int reps) throws Throwable {
		int sink = 0;
		for (int i = 0; i < reps / 10; i++) {
			sink += call.run();
		}
		long start = System.nanoTime();
		for (int i = 0; i < reps; i++) {
			sink += call.run();
		}
		long elapsed = System.nanoTime() - start;
		if (sink == Integer.MIN_VALUE) {
			throw new IllegalStateException();
		}
		return elapsed / (double) reps;
	}

}
