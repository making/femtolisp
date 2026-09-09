package am.ik.rontolisp.testsupport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The one tree the ci-spec corpus needs but no backend can build at run time.
 *
 * <p>
 * The {@code wild-pathnames} case walks a pathspec under {@code ./wpc-sub/} with a
 * {@code **} directory component: a bounded tree anchored to a directory the run owns, so
 * the walk enters exactly one directory and its answer cannot depend on what else the run
 * directory holds. A {@code :wild-inferiors} anchored at the run directory itself (a
 * {@code **} component in a pathspec starting at the working directory) instead reads
 * EVERY directory below the process working directory, which is the project root for the
 * in-process corpus runners -- on a box carrying agent checkouts under
 * {@code .claude/worktrees/} that is a walk of thousands of directories, and the case's
 * filter fixed the assertion, not the work.
 *
 * <p>
 * The tree is staged by the driver because neither WASM backend can create a directory
 * (the same reason {@code WasmLispCompilerIntegrationTest} builds its {@code wt} tree
 * with {@code mkdir} in the container). Every driver that RUNS the corpus must stage it
 * before the run: {@code CiSpecE2eTest} (the working directory is its {@code @TempDir})
 * and {@code JvmClassShakerCorpusTest} (the process working directory, the project root
 * -- which must also be cleaned).
 */
public final class CorpusFixtures {

	/** The directory name the {@code wild-pathnames} case anchors its walk to. */
	public static final String WILD_PATHNAME_DIR = "wpc-sub";

	private CorpusFixtures() {
	}

	/**
	 * Stages the {@code wild-pathnames} tree under the directory the corpus program will
	 * run in. Idempotent: safe to call for every leg of a driver.
	 * @param runDir the working directory the corpus program runs with
	 * @throws IOException if the tree cannot be staged
	 */
	public static void stageWildPathnameTree(Path runDir) throws IOException {
		Path wpc = runDir.resolve(WILD_PATHNAME_DIR);
		Files.createDirectories(wpc);
		Files.writeString(wpc.resolve("wpc-a.txt"), "a\n");
		Files.writeString(wpc.resolve("wpc-b.txt"), "b\n");
	}

	/**
	 * Removes the staged tree and its files. For drivers whose run directory is not
	 * itself disposable (the project root under the in-process corpus tests).
	 * @param runDir the directory the tree was staged into
	 * @throws IOException if the tree cannot be removed
	 */
	public static void removeWildPathnameTree(Path runDir) throws IOException {
		Path wpc = runDir.resolve(WILD_PATHNAME_DIR);
		Files.deleteIfExists(wpc.resolve("wpc-a.txt"));
		Files.deleteIfExists(wpc.resolve("wpc-b.txt"));
		Files.deleteIfExists(wpc);
	}

}
