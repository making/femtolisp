import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

import org.graalvm.nativeimage.c.function.CFunction;
import org.graalvm.nativeimage.c.function.CFunctionPointer;
import org.graalvm.nativeimage.c.function.InvokeCFunctionPointer;
import org.graalvm.nativeimage.c.type.CIntPointer;
import org.graalvm.word.WordFactory;

/**
 * The same libcuda call through SubstrateVM's own AOT native-call mechanism -- a
 * function pointer obtained at run time (dlsym through the FFM lookup) and invoked through
 * an {@code @InvokeCFunctionPointer} interface compiled at build time -- against the FFM
 * downcall handle. Native-image only. A probe.
 */
public class CFunctionPointerFloor {

	static final int REPS = 2_000_000;

	static final ValueLayout.OfInt I = ValueLayout.JAVA_INT;

	static final Linker LINKER = Linker.nativeLinker();

	static final SymbolLookup CUDA = SymbolLookup.libraryLookup("libcuda.so.1", Arena.global());

	static final MemorySegment CU_VERSION_ADDR = CUDA.find("cuDriverGetVersion").orElseThrow();

	static final MethodHandle CU_VERSION = LINKER.downcallHandle(CU_VERSION_ADDR,
			FunctionDescriptor.of(I, ValueLayout.ADDRESS));

	interface CuDriverGetVersion extends CFunctionPointer {

		@InvokeCFunctionPointer
		int call(CIntPointer out);

		@InvokeCFunctionPointer(transition = CFunction.Transition.NO_TRANSITION)
		int callNoTransition(CIntPointer out);

	}

	static MemorySegment OUT;

	static int viaPointer() {
		CuDriverGetVersion fp = WordFactory.pointer(CU_VERSION_ADDR.address());
		CIntPointer outp = WordFactory.pointer(OUT.address());
		return fp.call(outp);
	}

	static int viaPointerNoTransition() {
		CuDriverGetVersion fp = WordFactory.pointer(CU_VERSION_ADDR.address());
		CIntPointer outp = WordFactory.pointer(OUT.address());
		return fp.callNoTransition(outp);
	}

	public static void main(String[] args) throws Throwable {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment out = arena.allocate(I);
			OUT = out;
			for (int round = 0; round < 5; round++) {
				double a = ns(() -> (int) CU_VERSION.invokeExact(out));
				double b = ns(CFunctionPointerFloor::viaPointer);
				double c = ns(CFunctionPointerFloor::viaPointerNoTransition);
				double d = ns(() -> out.get(I, 0));
				System.out.printf("round %d  ffm downcall %8.1f   CFunctionPointer %8.1f   no-transition %8.1f   none %8.1f ns%n",
						round, a, b, c, d);
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
