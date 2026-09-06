import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/**
 * The per-call floor of one FFM downcall, held three ways: a {@code static final} handle,
 * a {@code final} INSTANCE field of an object reached through a {@code static final}
 * reference (the shape {@code am.ik.gpu.CudaDriver} had), and the same instance reached
 * through a MUTABLE static (the control: no JIT can fold that one).
 *
 * <p>
 * The call is {@code cuDriverGetVersion(int*)} -- the cheapest thing libcuda exports,
 * needs no context and no device, so what is measured is the invoker and the downcall
 * stub rather than anything the driver does.
 *
 * <p>
 * Not project code: a probe. Run it on both JITs --
 * {@code java HandleConstancy.java} and {@code java -XX:-UseJVMCICompiler HandleConstancy.java}.
 */
public class HandleConstancy {

	static final int REPS = 2_000_000;

	static final ValueLayout.OfInt I = ValueLayout.JAVA_INT;

	static final Linker LINKER = Linker.nativeLinker();

	static final SymbolLookup LOOKUP = SymbolLookup.libraryLookup("libcuda.so.1", Arena.global());

	static final FunctionDescriptor SHAPE = FunctionDescriptor.of(I, ValueLayout.ADDRESS);

	/** Arm 1: the handle itself is a constant. */
	static final MethodHandle STATIC_HANDLE = bind();

	/** Arm 4: the same, bound {@code critical(true)} -- no thread transition. */
	static final MethodHandle CRITICAL_HANDLE = LINKER.downcallHandle(LOOKUP.find("cuDriverGetVersion").orElseThrow(),
			SHAPE, Linker.Option.critical(true));

	/** Arm 2: a final instance field, the receiver reachable from a static final. */
	static final Holder CONSTANT_RECEIVER = new Holder();

	/** Arm 3: the same, through a receiver no JIT can fold. */
	static Holder mutableReceiver = new Holder();

	static MethodHandle bind() {
		return LINKER.downcallHandle(LOOKUP.find("cuDriverGetVersion").orElseThrow(), SHAPE);
	}

	static final class Holder {

		private final MethodHandle handle = bind();

		int call(MemorySegment out) throws Throwable {
			return (int) this.handle.invokeExact(out);
		}

	}

	static int viaStatic(MemorySegment out) throws Throwable {
		return (int) STATIC_HANDLE.invokeExact(out);
	}

	static int viaCritical(MemorySegment out) throws Throwable {
		return (int) CRITICAL_HANDLE.invokeExact(out);
	}

	/** The control: the same loop and the same interface, with no downcall in it. */
	static int viaNothing(MemorySegment out) {
		return out.get(I, 0);
	}

	public static void main(String[] args) throws Throwable {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment out = arena.allocate(I);
			for (int round = 0; round < 5; round++) {
				long a = time(() -> viaStatic(out));
				long b = time(() -> CONSTANT_RECEIVER.call(out));
				long c = time(() -> mutableReceiver.call(out));
				long d = time(() -> viaCritical(out));
				long e = time(() -> viaNothing(out));
				System.out.printf("round %d  static final %8.2f ns   constant receiver %8.2f ns"
						+ "   mutable receiver %8.2f ns   critical %8.2f ns   no downcall %8.2f ns%n", round,
						a / (double) REPS, b / (double) REPS, c / (double) REPS, d / (double) REPS,
						e / (double) REPS);
			}
		}
	}

	interface Call {

		int run() throws Throwable;

	}

	static long time(Call call) throws Throwable {
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
		return elapsed;
	}

}
