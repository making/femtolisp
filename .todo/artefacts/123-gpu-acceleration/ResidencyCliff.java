import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;

/**
 * {@code .todo/490} step 6: what happens to a decode loop when the model does NOT fit the
 * residency budget. Runs a class compiled with {@code --gpu} (its own embedded copy of
 * {@code am.ik.gpu}, renamed into the program's package) with the budget forced to a given
 * byte count through the package-private {@code residentBudget} seam the tests use, and
 * lets the program print its own tok/s. The override is applied the moment the embedded
 * {@code Gpu} class appears in the program's loader -- {@code _gpuInit} defines it at the
 * first device call, before any matrix has been uploaded -- so the whole run decodes under
 * the cap.
 *
 * <pre>
 * java --enable-native-access=ALL-UNNAMED --add-modules jdk.incubator.vector -Xmx16g \
 *   .todo/artefacts/123-gpu-acceleration/ResidencyCliff.java DIR CLASS BYTES program args...
 * </pre>
 *
 * {@code BYTES} of {@code -1} leaves the derived budget in force (the control run).
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
					try {
						gpu = Class.forName("RontoLispGpuGpu", false, loader);
					}
					catch (ClassNotFoundException e) {
						// not defined yet
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
				hits.setAccessible(true);
				misses.setAccessible(true);
				while (true) {
					Thread.sleep(5000);
					System.err.printf("[cliff] resident %d MB, hits %d, misses %d%n",
							(long) resident.invoke(dev) >> 20, hits.invoke(cache), misses.invoke(cache));
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

}
