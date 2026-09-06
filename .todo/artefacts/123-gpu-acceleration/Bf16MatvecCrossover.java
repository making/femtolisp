import am.ik.gpu.Gpu;

import java.util.Arrays;

/**
 * The device column for {@code .todo/490}: the SHIPPED bfloat16 GEMV route
 * ({@code Gpu.matvec(short[], ...)} over {@code am.ik.gpu}'s own classes, residency
 * included) against the shipped f32 one, shape by shape, as a decode loop calls it -- the
 * matrix resident from its second sight, {@code x} up, one launch, {@code y} down. Three
 * columns per shape: bf16 resident, f32 resident, and bf16 COLD (the matrix written
 * between calls, so the second sight pays its upload), min and median over three rounds.
 * Plus, at every shape, the equivalence the kernel promises: the bf16 result against the
 * f32 kernel over the widened matrix, counted in mismatching rows (must be 0).
 *
 * <p>
 * The CPU column is {@code matvec-bf16-baseline.lisp} under {@code --simd} on the JVM
 * class output; the threshold question is where "bf16 resident" first beats it clearly.
 * Run from the repository root against the built classes:
 *
 * <pre>
 * java --enable-native-access=ALL-UNNAMED -Xmx8g -cp target/classes .todo/artefacts/123-gpu-acceleration/Bf16MatvecCrossover.java
 * </pre>
 */
public class Bf16MatvecCrossover {

	/** matvec-baseline's shapes plus Qwen3.5-0.8B's and TinyLlama's own. */
	static final int[][] SHAPES = { { 256, 256 }, { 288, 288 }, { 384, 384 }, { 512, 512 }, { 768, 288 },
			{ 288, 768 }, { 768, 768 }, { 1024, 1024 }, { 2048, 1024 }, { 1024, 2048 }, { 3584, 1024 }, { 1024, 3584 },
			{ 6144, 1024 }, { 5632, 2048 }, { 2048, 5632 }, { 2048, 2048 }, { 4096, 4096 }, { 32000, 288 },
			{ 32000, 2048 }, { 248320, 1024 } };

	static short bf16(float value) {
		int bits = Float.floatToRawIntBits(value);
		return (short) ((bits + 0x7fff + ((bits >>> 16) & 1)) >>> 16);
	}

	public static void main(String[] args) throws Exception {
		System.out.println("device: " + Gpu.description());
		// Warm the call path on a small shape first (.kb/gpu.md: the first shape in a
		// process pays ~500 us a call for its first few thousand calls).
		{
			int rows = 512, cols = 512;
			short[] w = new short[rows * cols];
			float[] x = new float[cols], y = new float[rows];
			Gpu.matvec(w, 0, x, 0, y, 0, rows, cols);
			for (int i = 0; i < 3000; i++) {
				Gpu.matvec(w, 0, x, 0, y, 0, rows, cols);
			}
		}
		System.out.printf("%n%-12s %9s %8s | %9s %9s | %9s %9s | %9s %9s | %7s%n", "rows x cols", "elems", "bf16 MB",
				"bf16 min", "bf16 med", "f32 min", "f32 med", "cold min", "cold med", "mismatch");
		for (int[] s : SHAPES) {
			int rows = s[0], cols = s[1];
			long n = (long) rows * cols;
			short[] w = new short[(int) n];
			float[] wf = new float[(int) n], x = new float[cols], y = new float[rows], yf = new float[rows];
			for (int i = 0; i < n; i++) {
				w[i] = bf16((float) Math.sin(i * 0.37));
				wf[i] = Float.intBitsToFloat(w[i] << 16);
			}
			for (int j = 0; j < cols; j++) {
				x[j] = (float) Math.cos(j * 0.11);
			}
			int reps = n <= 1 << 20 ? 300 : (n <= 1 << 24 ? 100 : 30);
			double[] bf = timeResident(w, wf, x, y, rows, cols, reps, true);
			double[] f32 = timeResident(w, wf, x, yf, rows, cols, reps, false);
			int mismatch = 0;
			for (int r = 0; r < rows; r++) {
				if (Float.floatToRawIntBits(y[r]) != Float.floatToRawIntBits(yf[r])) {
					mismatch++;
				}
			}
			double[] cold = timeCold(w, x, y, rows, cols, Math.max(5, reps / 10));
			System.out.printf("%-12s %9d %8.1f | %9.1f %9.1f | %9.1f %9.1f | %9.1f %9.1f | %7d%n",
					rows + "x" + cols, n, n * 2 / 1e6, bf[0], bf[1], f32[0], f32[1], cold[0], cold[1], mismatch);
			Gpu.written(w);
			Gpu.written(wf);
		}
	}

	/** {min, median} us per call over three rounds, the matrix resident. */
	static double[] timeResident(short[] w, float[] wf, float[] x, float[] y, int rows, int cols, int reps,
			boolean bf16) throws Exception {
		if (!(bf16 ? Gpu.matvec(w, 0, x, 0, y, 0, rows, cols) : Gpu.matvec(wf, 0, x, 0, y, 0, rows, cols))) {
			// The first sight declines and marks; the second uploads.
			if (!(bf16 ? Gpu.matvec(w, 0, x, 0, y, 0, rows, cols) : Gpu.matvec(wf, 0, x, 0, y, 0, rows, cols))) {
				return new double[] { Double.NaN, Double.NaN };
			}
		}
		for (int i = 0; i < Math.min(100, reps); i++) {
			if (bf16) {
				Gpu.matvec(w, 0, x, 0, y, 0, rows, cols);
			}
			else {
				Gpu.matvec(wf, 0, x, 0, y, 0, rows, cols);
			}
		}
		double min = Double.MAX_VALUE, median = Double.MAX_VALUE;
		double[] t = new double[reps];
		for (int round = 0; round < 3; round++) {
			for (int i = 0; i < reps; i++) {
				long t0 = System.nanoTime();
				boolean ok = bf16 ? Gpu.matvec(w, 0, x, 0, y, 0, rows, cols) : Gpu.matvec(wf, 0, x, 0, y, 0, rows, cols);
				t[i] = (System.nanoTime() - t0) / 1e3;
				if (!ok) {
					throw new IllegalStateException("declined mid-run at " + rows + "x" + cols);
				}
			}
			double[] sorted = t.clone();
			Arrays.sort(sorted);
			min = Math.min(min, sorted[0]);
			median = Math.min(median, sorted[reps / 2]);
		}
		return new double[] { min, median };
	}

	/** {min, median} us of the UPLOADING call: written, declined once, then timed. */
	static double[] timeCold(short[] w, float[] x, float[] y, int rows, int cols, int reps) {
		double[] t = new double[reps];
		for (int i = 0; i < reps; i++) {
			Gpu.written(w);
			Gpu.matvec(w, 0, x, 0, y, 0, rows, cols); // the first sight declines
			long t0 = System.nanoTime();
			if (!Gpu.matvec(w, 0, x, 0, y, 0, rows, cols)) {
				// Below the size threshold: declined at every sight.
				return new double[] { Double.NaN, Double.NaN };
			}
			t[i] = (System.nanoTime() - t0) / 1e3;
		}
		double[] sorted = t.clone();
		Arrays.sort(sorted);
		return new double[] { sorted[0], sorted[reps / 2] };
	}

}
