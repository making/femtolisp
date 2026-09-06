import java.lang.foreign.*;
import java.nio.file.*;

/**
 * The bfloat16 GEMV's load-width question ({@code .todo/490}, {@code gemv-bf16-probe.cu}):
 * kernel-only time -- launch plus synchronize over RESIDENT buffers, no copies -- for the
 * shipped one-pattern-per-lane bf16 kernel against 2 / 4 / 8 patterns per lane, and the
 * f32 kernel against its float4 sibling, at the shapes where the shipped route first
 * showed the bf16 kernel at half the device's bandwidth. Then, for each bf16 candidate,
 * whether it lands on its f32 sibling's bits over the widened matrix (the equivalence the
 * shipped pair is pinned by), and the bandwidth each reaches.
 *
 * <pre>
 * cd .todo/artefacts/123-gpu-acceleration
 * nvcc -arch=compute_75 -ptx -fmad=false gemv-bf16-probe.cu -o gemv-bf16-probe.ptx
 * java --enable-native-access=ALL-UNNAMED -Xmx8g Bf16KernelProbe.java
 * </pre>
 */
public class Bf16KernelProbe {

	static final int[][] SHAPES = { { 1024, 1024 }, { 3584, 1024 }, { 6144, 1024 }, { 2048, 5632 }, { 4096, 4096 },
			{ 32000, 2048 }, { 248320, 1024 } };

	static final String[] BF16 = { "gemv_bf16_x1", "gemv_bf16_x2", "gemv_bf16_x4", "gemv_bf16_x8", "gemv_bf16_ff",
			"gemv_bf16_f" };

	static final String[] F32 = { "gemv_f32_x1", "gemv_f32_x4", "gemv_f32_ff" };

	static short bf16(float value) {
		int bits = Float.floatToRawIntBits(value);
		return (short) ((bits + 0x7fff + ((bits >>> 16) & 1)) >>> 16);
	}

	public static void main(String[] args) throws Throwable {
		CuLib.open();
		MemorySegment mod = Arena.global().allocate(CuLib.P);
		CuLib.ck((int) CuLib.cuModuleLoadData.invoke(mod,
				Arena.global().allocateFrom(Files.readString(Path.of("gemv-bf16-probe.ptx")))), "cuModuleLoadData");
		CuLib.module = mod.get(CuLib.P, 0);
		MemorySegment[] bf = new MemorySegment[BF16.length], f = new MemorySegment[F32.length];
		for (int i = 0; i < BF16.length; i++) {
			bf[i] = CuLib.func(BF16[i]);
		}
		for (int i = 0; i < F32.length; i++) {
			f[i] = CuLib.func(F32[i]);
		}
		System.out.printf("%n%-12s %8s |", "rows x cols", "bf16 MB");
		for (String k : BF16) {
			System.out.printf(" %13s", k.substring(5));
		}
		System.out.printf(" |");
		for (String k : F32) {
			System.out.printf(" %13s", k.substring(5));
		}
		System.out.printf("   (us/call, GB/s)%n");
		for (int[] s : SHAPES) {
			int rows = s[0], cols = s[1];
			long n = (long) rows * cols;
			short[] w = new short[(int) n];
			float[] wf = new float[(int) n], x = new float[cols];
			for (int i = 0; i < n; i++) {
				w[i] = bf16((float) Math.sin(i * 0.37));
				wf[i] = Float.intBitsToFloat(w[i] << 16);
			}
			for (int j = 0; j < cols; j++) {
				x[j] = (float) Math.cos(j * 0.11);
			}
			int reps = n <= 1 << 22 ? 400 : (n <= 1 << 26 ? 100 : 30);
			try (Arena arena = Arena.ofConfined()) {
				long dw = CuLib.alloc(arena, n * 2, true), dwf = CuLib.alloc(arena, n * 4, true),
						dx = CuLib.alloc(arena, (long) cols * 4, true), dy = CuLib.alloc(arena, (long) rows * 4, true);
				CuLib.ck((int) CuLib.cuMemcpyHtoDc.invoke(dw, MemorySegment.ofArray(w), n * 2), "htod");
				CuLib.ck((int) CuLib.cuMemcpyHtoDc.invoke(dwf, MemorySegment.ofArray(wf), n * 4), "htod");
				CuLib.ck((int) CuLib.cuMemcpyHtoDc.invoke(dx, MemorySegment.ofArray(x), (long) cols * 4), "htod");
				System.out.printf("%-12s %8.1f |", rows + "x" + cols, n * 2 / 1e6);
				float[][] results = new float[BF16.length][rows];
				for (int i = 0; i < BF16.length; i++) {
					double us = time(bf[i], dw, dx, dy, rows, cols, reps);
					System.out.printf(" %6.1f %6.0f", us, n * 2 / us / 1e3);
					CuLib.ck((int) CuLib.cuMemcpyDtoHc.invoke(MemorySegment.ofArray(results[i]), dy, (long) rows * 4),
							"dtoh");
				}
				System.out.printf(" |");
				float[][] resultsF = new float[F32.length][rows];
				for (int i = 0; i < F32.length; i++) {
					double us = time(f[i], dwf, dx, dy, rows, cols, reps);
					System.out.printf(" %6.1f %6.0f", us, n * 4 / us / 1e3);
					CuLib.ck((int) CuLib.cuMemcpyDtoHc.invoke(MemorySegment.ofArray(resultsF[i]), dy, (long) rows * 4),
							"dtoh");
				}
				// x1 must equal f32_x1 (the shipped equivalence); x4 must equal f32_x4 (the
				// same order); ff must equal f32_ff; and every kernel against the host's
				// double-accumulated oracle (the scalar defun's rule), in rows that differ.
				float[] oracle = new float[rows];
				for (int r = 0; r < rows; r++) {
					double acc = 0;
					for (int j = 0; j < cols; j++) {
						acc += (double) wf[r * cols + j] * x[j];
					}
					oracle[r] = (float) acc;
				}
				System.out.printf("   x1==f32x1 %d, x4==f32x4 %d, ff==f32ff %d | vs oracle: x1 %d x4 %d ff %d f %d, f32 x1 %d ff %d%n",
						differ(results[0], resultsF[0]), differ(results[2], resultsF[1]), differ(results[4], resultsF[2]),
						differ(results[0], oracle), differ(results[2], oracle), differ(results[4], oracle),
						differ(results[5], oracle), differ(resultsF[0], oracle), differ(resultsF[2], oracle));
				CuLib.free(dw, true);
				CuLib.free(dwf, true);
				CuLib.free(dx, true);
				CuLib.free(dy, true);
			}
		}
	}

	static int differ(float[] a, float[] b) {
		int d = 0;
		for (int i = 0; i < a.length; i++) {
			if (Float.floatToRawIntBits(a[i]) != Float.floatToRawIntBits(b[i])) {
				d++;
			}
		}
		return d;
	}

	/** Best-of-reps kernel time: launch + synchronize, no copies. */
	static double time(MemorySegment kernel, long dw, long dx, long dy, int rows, int cols, int reps)
			throws Throwable {
		for (int i = 0; i < 20; i++) {
			try (Arena a = Arena.ofConfined()) {
				launch(kernel, dw, dx, dy, rows, cols, a);
			}
		}
		CuLib.ck((int) CuLib.cuCtxSynchronize.invoke(), "sync");
		return CuLib.best(reps, () -> {
			try (Arena a = Arena.ofConfined()) {
				launch(kernel, dw, dx, dy, rows, cols, a);
				CuLib.ck((int) CuLib.cuCtxSynchronize.invoke(), "sync");
			}
		});
	}

	static void launch(MemorySegment kernel, long dw, long dx, long dy, int rows, int cols, Arena arena)
			throws Throwable {
		MemorySegment pw = arena.allocate(CuLib.L), px = arena.allocate(CuLib.L), py = arena.allocate(CuLib.L);
		pw.set(CuLib.L, 0, dw);
		px.set(CuLib.L, 0, dx);
		py.set(CuLib.L, 0, dy);
		MemorySegment pr = arena.allocate(CuLib.I), pc = arena.allocate(CuLib.I);
		pr.set(CuLib.I, 0, rows);
		pc.set(CuLib.I, 0, cols);
		MemorySegment params = arena.allocate(CuLib.P, 5);
		params.setAtIndex(CuLib.P, 0, pw);
		params.setAtIndex(CuLib.P, 1, px);
		params.setAtIndex(CuLib.P, 2, py);
		params.setAtIndex(CuLib.P, 3, pr);
		params.setAtIndex(CuLib.P, 4, pc);
		int block = 256, warps = block / 32;
		CuLib.ck((int) CuLib.cuLaunchKernel.invoke(kernel, (rows + warps - 1) / warps, 1, 1, block, 1, 1, 0,
				MemorySegment.NULL, params, MemorySegment.NULL), "launch");
	}

}
