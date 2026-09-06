import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;

/**
 * {@code .todo/490} step 6: what happens to a decode loop when the model does NOT fit the
 * residency budget. Runs a program with the residency budget forced to a given byte count
 * through the package-private {@code residentBudget} seam the tests use, and lets the
 * program print its own tok/s. The override is applied the moment the {@code Gpu} class
 * appears in the program's loader -- before any matrix has been uploaded -- so the whole
 * run decodes under the cap.
 *
 * <p>
 * Both halves of the flag are drivable, because the budget belongs to the library and the
 * library is the same bytes on each: the JVM class output carries its own copy of
 * {@code am.ik.gpu} renamed into the program's package ({@code RontoLispGpuGpu}), and the
 * interpreter is the CLI's own {@code am.ik.gpu.Gpu} out of the executable jar. The
 * watcher looks for both names.
 *
 * <pre>
 * # the JVM class output: DIR holds Prog.class
 * java --enable-native-access=ALL-UNNAMED --add-modules jdk.incubator.vector -Xmx16g \
 *   .todo/artefacts/123-gpu-acceleration/ResidencyCliff.java DIR Prog BYTES program args...
 *
 * # the interpreter: DIR is the executable jar, and the program args are the CLI's own
 * java --enable-native-access=ALL-UNNAMED --add-modules jdk.incubator.vector -Xmx16g \
 *   .todo/artefacts/123-gpu-acceleration/ResidencyCliff.java target/rontolisp-0.1.0-SNAPSHOT-exec.jar \
 *   am.ik.rontolisp.cli.RontoLispCli BYTES llm.lisp --gpu --simd -- stories15M.bin -t 0 -n 64
 * </pre>
 *
 * {@code BYTES} of {@code -1} leaves the derived budget in force (the control run). The
 * budget actually in force is printed as soon as the device derives one, so a control run
 * says what it ran under too ({@code .todo/716}: a run has to be able to say this).
 */
public class ResidencyCliff {

	public static void main(String[] args) throws Exception {
		Path dir = Path.of(args[0]);
		String className = args[1];
		long bytes = Long.parseLong(args[2]);
		String[] rest = java.util.Arrays.copyOfRange(args, 3, args.length);
		URLClassLoader loader = new URLClassLoader(new URL[] { dir.toUri().toURL() },
				ClassLoader.getSystemClassLoader());
		Class<?> program = loader.loadClass(className);
		Method main = program.getMethod("main", String[].class);
		Thread watcher = new Thread(() -> {
			try {
				Class<?> gpu = null;
				while (gpu == null) {
					Thread.sleep(2);
					gpu = defined(loader, "RontoLispGpuGpu");
					if (gpu == null) {
						gpu = defined(loader, "am.ik.gpu.Gpu");
					}
				}
				Method device = gpu.getDeclaredMethod("device");
				device.setAccessible(true);
				Object dev = null;
				while (dev == null) {
					dev = device.invoke(null);
					Thread.sleep(2);
				}
				if (bytes >= 0) {
					Method budget = dev.getClass().getDeclaredMethod("residentBudget", long.class);
					budget.setAccessible(true);
					budget.invoke(dev, bytes);
				}
				System.err.println("[cliff] device " + dev.getClass().getSimpleName() + ", budget override "
						+ (bytes < 0 ? "none" : bytes / (1 << 20) + " MB"));
				Method resident = dev.getClass().getMethod("residentBytes");
				Method residency = dev.getClass().getMethod("residency");
				resident.setAccessible(true); // the class itself is package-private
				residency.setAccessible(true);
				Object cache = residency.invoke(dev);
				Method hits = cache.getClass().getDeclaredMethod("hits");
				Method misses = cache.getClass().getDeclaredMethod("misses");
				Method budget = cache.getClass().getDeclaredMethod("budget");
				Method evictions = cache.getClass().getDeclaredMethod("evictions");
				Method reuploads = cache.getClass().getDeclaredMethod("reuploads");
				Method reuploadedBytes = cache.getClass().getDeclaredMethod("reuploadedBytes");
				for (Method each : new Method[] { hits, misses, budget, evictions, reuploads, reuploadedBytes }) {
					each.setAccessible(true);
				}
				long announced = -1;
				for (int tick = 0;; tick++) {
					// The budget is derived at the first pre-flight, not at the probe, so
					// it is announced when it appears rather than up front -- and polled
					// often enough that a run of a few seconds still says what it ran
					// under.
					long inForce = (long) budget.invoke(cache);
					if (inForce != announced) {
						announced = inForce;
						System.err.println("[cliff] budget in force: " + (inForce >> 20) + " MB");
					}
					if (tick % 20 == 19) {
						System.err.printf("[cliff] resident %d MB, hits %d, misses %d, evictions %d, re-uploads %d"
								+ " (%d MB)%n", (long) resident.invoke(dev) >> 20, hits.invoke(cache),
								misses.invoke(cache), evictions.invoke(cache), reuploads.invoke(cache),
								(long) reuploadedBytes.invoke(cache) >> 20);
					}
					Thread.sleep(250);
				}
			}
			catch (Exception e) {
				System.err.println("[cliff] watcher: " + e);
			}
		});
		watcher.setDaemon(true);
		watcher.start();
		main.invoke(null, (Object) rest);
	}

	/** The class if the program's loader has it by now, or {@code null}. */
	private static Class<?> defined(ClassLoader loader, String name) {
		try {
			return Class.forName(name, false, loader);
		}
		catch (ClassNotFoundException e) {
			return null;
		}
	}

}
