import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.file.*;
import java.util.*;

/**
 * The Q4 ceiling on the device, MEASURED ({@code .todo/726}, {@code gemv-q4-probe.cu}):
 * device-side kernel time (CUDA events around one launch) of the shipped {@code gemv_bf16}
 * and {@code gemv_f32} against Q8_0 and Q4_0 GEMV kernels over ggml's block layouts (an f32
 * activation, and the {@code _dp} integer-dot shape over a Q8-quantized one), at the
 * seven shapes a Qwen3.5-0.8B forward pass launches on the device and with each shape's
 * launch count, so the last table is ms of GEMV a FORWARD PASS at each width -- the number
 * the refusal in {@code .kb/gpu.md} was scaling from bytes.
 *
 * <p>
 * "Cold" rotates the launch over enough copies of the matrix that the working set is
 * several times the L2 (the decode step streams the whole 1.5 GB model through a token, so
 * nothing but the vector is ever cached); "hot" re-launches over one copy, which is what a
 * microbenchmark measures and what {@code Bf16MatvecCrossover}'s "L2" shapes were. The
 * bf16 cold column reproduces the in-situ {@code nsys} durations of {@code .todo/718}
 * (33.3 us at 3584x1024, 56.7 at 6144x1024, 2.16 ms for the head), which is the check that
 * the cold method measures what the decode step pays.
 *
 * <pre>
 * cd .todo/artefacts/123-gpu-acceleration
 * nvcc -arch=compute_75 -ptx -fmad=false gemv-q4-probe.cu -o gemv-q4-probe.ptx
 * java --enable-native-access=ALL-UNNAMED -Xmx8g Q4KernelProbe.java
 * </pre>
 */
public class Q4KernelProbe {

	/** rows, cols, bf16 launches a forward (Qwen3.5-0.8B after .todo/725: 157 in all). */
	static final int[][] SHAPES = { { 248320, 1024, 1 }, { 6144, 1024, 18 }, { 3584, 1024, 48 }, { 1024, 3584, 24 },
			{ 1024, 2048, 24 }, { 2048, 1024, 30 }, { 512, 1024, 12 } };

	static final String[] Q8 = { "gemv_q8_0_l1", "gemv_q8_0_l2", "gemv_q8_0_l4", "gemv_q8_0_l8", "gemv_q8_0_split" };

	static final String[] Q4 = { "gemv_q4_0_l1", "gemv_q4_0_l2", "gemv_q4_0_l4", "gemv_q4_0_split", "gemv_q4_0_ff" };

	/** The integer-dot shape over a Q8-quantized activation (the CPU contract's). */
	static final String[] DP = { "gemv_q8_0_dp8", "gemv_q8_0_dp4", "gemv_q4_0_dp4", "gemv_q4_0_dp2" };

	/** Bytes the rotation set must cover, several times any L2 this device could have. */
	static final long COLD_BYTES = 256L << 20;

	static final MethodHandle cuEventCreate = CuLib.h("cuEventCreate",
			FunctionDescriptor.of(CuLib.I, CuLib.P, CuLib.I));

	static final MethodHandle cuEventRecord = CuLib.h("cuEventRecord",
			FunctionDescriptor.of(CuLib.I, CuLib.P, CuLib.P));

	static final MethodHandle cuEventSynchronize = CuLib.h("cuEventSynchronize",
			FunctionDescriptor.of(CuLib.I, CuLib.P));

	static final MethodHandle cuEventElapsedTime = CuLib.h("cuEventElapsedTime",
			FunctionDescriptor.of(CuLib.I, CuLib.P, CuLib.P, CuLib.P));

	static MemorySegment e0, e1;

	public static void main(String[] args) throws Throwable {
		CuLib.open();
		MemorySegment attr = Arena.global().allocate(CuLib.I);
		CuLib.cuDeviceGetAttribute.invoke(attr, 38, CuLib.device); // CU_DEVICE_ATTRIBUTE_L2_CACHE_SIZE
		System.out.printf("L2 %d MB; cold sets of >= %d MB%n", attr.get(CuLib.I, 0) >> 20, COLD_BYTES >> 20);
		MemorySegment shipped = CuLib.module;
		MemorySegment mod = Arena.global().allocate(CuLib.P);
		CuLib.ck((int) CuLib.cuModuleLoadData.invoke(mod,
				Arena.global().allocateFrom(Files.readString(Path.of("gemv-q4-probe.ptx")))), "cuModuleLoadData");
		CuLib.module = mod.get(CuLib.P, 0);
		MemorySegment[] q8 = new MemorySegment[Q8.length], q4 = new MemorySegment[Q4.length],
				dp = new MemorySegment[DP.length];
		for (int i = 0; i < Q8.length; i++) {
			q8[i] = CuLib.func(Q8[i]);
		}
		for (int i = 0; i < Q4.length; i++) {
			q4[i] = CuLib.func(Q4[i]);
		}
		for (int i = 0; i < DP.length; i++) {
			dp[i] = CuLib.func(DP[i]);
		}
		CuLib.module = shipped;
		MemorySegment bf16 = CuLib.func("gemv_bf16"), f32 = CuLib.func("gemv_f32");
		MemorySegment ev = Arena.global().allocate(CuLib.P);
		CuLib.ck((int) cuEventCreate.invoke(ev, 0), "cuEventCreate");
		e0 = ev.get(CuLib.P, 0);
		CuLib.ck((int) cuEventCreate.invoke(ev, 0), "cuEventCreate");
		e1 = ev.get(CuLib.P, 0);

		double[] perForwardCold = new double[2 + Q8.length + Q4.length + DP.length];
		double[] perForwardBest = new double[perForwardCold.length];
		String[] names = new String[perForwardCold.length];
		names[0] = "f32";
		names[1] = "bf16";
		for (int i = 0; i < Q8.length; i++) {
			names[2 + i] = Q8[i].substring(5);
		}
		for (int i = 0; i < Q4.length; i++) {
			names[2 + Q8.length + i] = Q4[i].substring(5);
		}
		for (int i = 0; i < DP.length; i++) {
			names[2 + Q8.length + Q4.length + i] = DP[i].substring(5);
		}
		for (int[] s : SHAPES) {
			int rows = s[0], cols = s[1], launches = s[2];
			int nb = cols / 32;
			long n = (long) rows * cols;
			float[] wf = new float[(int) n], x = new float[cols];
			for (int i = 0; i < n; i++) {
				wf[i] = (float) Math.sin(i * 0.37) * (1 + 0.5f * (float) Math.cos(i * 0.0013));
			}
			for (int j = 0; j < cols; j++) {
				x[j] = (float) Math.cos(j * 0.11);
			}
			short[] wb = new short[(int) n];
			float[] wbf = new float[(int) n];
			for (int i = 0; i < n; i++) {
				wb[i] = bf16(wf[i]);
				wbf[i] = Float.intBitsToFloat(wb[i] << 16);
			}
			byte[] q8b = new byte[(int) (rows * (long) nb * 34)], q4b = new byte[(int) (rows * (long) nb * 18)];
			float[] q8d = new float[(int) n], q4d = new float[(int) n];
			quantize(wf, rows, cols, q8b, q4b, q8d, q4d);
			int sw = (nb + 7) & ~7;
			byte[] q8s = new byte[(int) (rows * (long) (sw * 2 + nb * 32))],
					q4s = new byte[(int) (rows * (long) (sw * 2 + nb * 16))];
			split(q8b, q8s, rows, nb, 34, 32, sw);
			split(q4b, q4s, rows, nb, 18, 16, sw);

			byte[] xq = new byte[cols];
			float[] xs = new float[nb];
			quantizeX(x, xq, xs);
			int xpad = (nb * 4 + 15) & ~15;
			byte[] xpack = new byte[xpad + cols];
			java.nio.ByteBuffer.wrap(xpack).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer().put(xs);
			System.arraycopy(xq, 0, xpack, xpad, cols);

			long bf16Bytes = n * 2;
			int copies = (int) Math.max(1, Math.min(512, (COLD_BYTES + bf16Bytes - 1) / bf16Bytes));
			System.out.printf("%n== %dx%d, x%d a forward: bf16 %.2f MB, q8_0 %.2f MB, q4_0 %.2f MB, %d copies%n",
					rows, cols, launches, bf16Bytes / 1e6, q8b.length / 1e6, q4b.length / 1e6, copies);
			System.out.printf("%-12s %10s %10s %10s %10s   %10s %10s %s%n", "kernel", "cold med", "cold min",
					"hot med", "hot min", "GB/s cold", "GB/s hot", "max |err| / sum|terms|");
			try (Arena arena = Arena.ofConfined()) {
				long dx = CuLib.alloc(arena, (long) cols * 4, true), dy = CuLib.alloc(arena, (long) rows * 4, true);
				CuLib.ck((int) CuLib.cuMemcpyHtoDc.invoke(dx, MemorySegment.ofArray(x), (long) cols * 4), "htod");
				long dxq = CuLib.alloc(arena, xpack.length, true);
				CuLib.ck((int) CuLib.cuMemcpyHtoDc.invoke(dxq, MemorySegment.ofArray(xpack), xpack.length), "htod");
				double[][] oracleBf = oracle(wbf, x, rows, cols), oracleQ8 = oracle(q8d, x, rows, cols),
						oracleQ4 = oracle(q4d, x, rows, cols), oracleQ8x = oracleDp(q8b, 34, xq, xs, rows, cols),
						oracleQ4x = oracleDp(q4b, 18, xq, xs, rows, cols);
				int k = 0;
				// f32 and bf16: the shipped kernels
				k = one(k, "f32", f32, MemorySegment.ofArray(wbf), n * 4, copies, dx, dy, rows, cols, launches,
						oracleBf, perForwardCold, perForwardBest, arena);
				k = one(k, "bf16", bf16, MemorySegment.ofArray(wb), n * 2, copies, dx, dy, rows, cols, launches,
						oracleBf, perForwardCold, perForwardBest, arena);
				for (int i = 0; i < Q8.length; i++) {
					boolean sp = Q8[i].endsWith("split");
					byte[] src = sp ? q8s : q8b;
					k = one(k, Q8[i].substring(5), q8[i], MemorySegment.ofArray(src), src.length, copies, dx, dy,
							rows, cols, launches, oracleQ8, perForwardCold, perForwardBest, arena);
				}
				for (int i = 0; i < Q4.length; i++) {
					boolean sp = Q4[i].endsWith("split");
					byte[] src = sp ? q4s : q4b;
					k = one(k, Q4[i].substring(5), q4[i], MemorySegment.ofArray(src), src.length, copies, dx, dy,
							rows, cols, launches, oracleQ4, perForwardCold, perForwardBest, arena);
				}
				for (int i = 0; i < DP.length; i++) {
					boolean q4k = DP[i].startsWith("gemv_q4");
					byte[] src = q4k ? q4b : q8b;
					k = one(k, DP[i].substring(5), dp[i], MemorySegment.ofArray(src), src.length, copies, dxq, dy,
							rows, cols, launches, q4k ? oracleQ4x : oracleQ8x, perForwardCold, perForwardBest, arena);
				}
				CuLib.free(dxq, true);
				CuLib.free(dx, true);
				CuLib.free(dy, true);
			}
		}
		System.out.printf("%n== GEMV kernel time a FORWARD PASS (157 launches), ms: sum over shapes of count x cold median%n");
		System.out.printf("%-12s %10s %10s%n", "kernel", "cold med", "cold min");
		for (int i = 0; i < names.length; i++) {
			System.out.printf("%-12s %10.3f %10.3f%n", names[i], perForwardCold[i], perForwardBest[i]);
		}
	}

	static int one(int k, String name, MemorySegment kernel, MemorySegment host, long bytes, int copies, long dx,
			long dy, int rows, int cols, int launches, double[][] oracle, double[] cold, double[] best, Arena arena)
			throws Throwable {
		long[] d = new long[copies];
		for (int c = 0; c < copies; c++) {
			d[c] = CuLib.alloc(arena, bytes, true);
			CuLib.ck((int) CuLib.cuMemcpyHtoDc.invoke(d[c], host, bytes), "htod");
		}
		CuLib.ck((int) CuLib.cuCtxSynchronize.invoke(), "sync");
		for (int i = 0; i < 10; i++) {
			timed(kernel, d[i % copies], dx, dy, rows, cols);
		}
		int reps = Math.max(copies, 40);
		double[] tc = new double[reps];
		for (int r = 0; r < reps; r++) {
			tc[r] = timed(kernel, d[r % copies], dx, dy, rows, cols);
		}
		double[] th = new double[40];
		for (int r = 0; r < th.length; r++) {
			th[r] = timed(kernel, d[0], dx, dy, rows, cols);
		}
		float[] y = new float[rows];
		CuLib.ck((int) CuLib.cuMemcpyDtoHc.invoke(MemorySegment.ofArray(y), dy, (long) rows * 4), "dtoh");
		double maxRel = 0;
		for (int r = 0; r < rows; r++) {
			double rel = Math.abs(y[r] - oracle[0][r]) / Math.max(oracle[1][r], 1e-30);
			maxRel = Math.max(maxRel, rel);
		}
		Arrays.sort(tc);
		Arrays.sort(th);
		double cm = tc[tc.length / 2], hm = th[th.length / 2];
		System.out.printf("%-12s %10.1f %10.1f %10.1f %10.1f   %10.0f %10.0f   %.2e%s%n", name, cm, tc[0], hm, th[0],
				bytes / cm / 1e3, bytes / hm / 1e3, maxRel, maxRel > 1e-4 ? "  <-- WRONG" : "");
		cold[k] += launches * cm / 1e3;
		best[k] += launches * tc[0] / 1e3;
		for (int c = 0; c < copies; c++) {
			CuLib.free(d[c], true);
		}
		return k + 1;
	}

	/** One launch bracketed by events: device-side duration in microseconds. */
	static double timed(MemorySegment kernel, long dw, long dx, long dy, int rows, int cols) throws Throwable {
		try (Arena a = Arena.ofConfined()) {
			CuLib.ck((int) cuEventRecord.invoke(e0, MemorySegment.NULL), "record");
			launch(kernel, dw, dx, dy, rows, cols, a);
			CuLib.ck((int) cuEventRecord.invoke(e1, MemorySegment.NULL), "record");
			CuLib.ck((int) cuEventSynchronize.invoke(e1), "event sync");
			MemorySegment ms = a.allocate(ValueLayout.JAVA_FLOAT);
			CuLib.ck((int) cuEventElapsedTime.invoke(ms, e0, e1), "elapsed");
			return ms.get(ValueLayout.JAVA_FLOAT, 0) * 1e3;
		}
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

	static short bf16(float value) {
		int bits = Float.floatToRawIntBits(value);
		return (short) ((bits + 0x7fff + ((bits >>> 16) & 1)) >>> 16);
	}

	/** Per row: the double-accumulated product, and the sum of |terms| it is judged against. */
	static double[][] oracle(float[] w, float[] x, int rows, int cols) {
		double[] y = new double[rows], norm = new double[rows];
		for (int r = 0; r < rows; r++) {
			double acc = 0, n = 0;
			for (int j = 0; j < cols; j++) {
				double t = (double) w[r * cols + j] * x[j];
				acc += t;
				n += Math.abs(t);
			}
			y[r] = acc;
			norm[r] = n;
		}
		return new double[][] { y, norm };
	}

	/** The integer-dot shape's own oracle: sum over blocks of d * sx * (exact integer dot). */
	static double[][] oracleDp(byte[] blocks, int blockBytes, byte[] xq, float[] xs, int rows, int cols) {
		int nb = cols / 32;
		double[] y = new double[rows], norm = new double[rows];
		for (int r = 0; r < rows; r++) {
			double acc = 0, n = 0;
			for (int b = 0; b < nb; b++) {
				int o = (r * nb + b) * blockBytes;
				double d = Float.float16ToFloat((short) ((blocks[o] & 0xFF) | (blocks[o + 1] << 8))) * xs[b];
				long dot = 0, adot = 0;
				for (int i = 0; i < 32; i++) {
					int q = blockBytes == 34 ? blocks[o + 2 + i]
							: (i < 16 ? (blocks[o + 2 + i] & 0xF) : ((blocks[o + 2 + i - 16] >> 4) & 0xF)) - 8;
					dot += (long) q * xq[b * 32 + i];
					adot += Math.abs((long) q * xq[b * 32 + i]);
				}
				acc += d * dot;
				n += Math.abs(d) * adot;
			}
			y[r] = acc;
			norm[r] = n;
		}
		return new double[][] { y, norm };
	}

	/** The CPU contract's activation quantizer: amax / 127 in double, CL round = rint. */
	static void quantizeX(float[] x, byte[] xq, float[] xs) {
		for (int b = 0; b < xs.length; b++) {
			float amax = 0;
			for (int i = 0; i < 32; i++) {
				amax = Math.max(amax, Math.abs(x[b * 32 + i]));
			}
			double sx = amax / 127.0;
			xs[b] = (float) sx;
			for (int i = 0; i < 32; i++) {
				xq[b * 32 + i] = (byte) (sx == 0 ? 0 : (int) Math.rint(x[b * 32 + i] / sx));
			}
		}
	}

	/** ggml's quantize_row_q8_0_ref and quantize_row_q4_0_ref, and what each block denotes. */
	static void quantize(float[] w, int rows, int cols, byte[] q8, byte[] q4, float[] q8d, float[] q4d) {
		int nb = cols / 32;
		for (int r = 0; r < rows; r++) {
			for (int b = 0; b < nb; b++) {
				int base = r * cols + b * 32;
				float amax = 0, max = 0;
				for (int i = 0; i < 32; i++) {
					float v = w[base + i];
					if (Math.abs(v) > amax) {
						amax = Math.abs(v);
						max = v;
					}
				}
				// Q8_0
				float d8 = amax / 127f, id8 = d8 != 0 ? 1f / d8 : 0f;
				short h8 = Float.floatToFloat16(d8);
				float dd8 = Float.float16ToFloat(h8);
				int o8 = (r * nb + b) * 34;
				q8[o8] = (byte) h8;
				q8[o8 + 1] = (byte) (h8 >> 8);
				for (int i = 0; i < 32; i++) {
					float v = w[base + i] * id8;
					int q = v < 0 ? -Math.round(-v) : Math.round(v);
					q8[o8 + 2 + i] = (byte) q;
					q8d[base + i] = q * dd8;
				}
				// Q4_0
				float d4 = max / -8f, id4 = d4 != 0 ? 1f / d4 : 0f;
				short h4 = Float.floatToFloat16(d4);
				float dd4 = Float.float16ToFloat(h4);
				int o4 = (r * nb + b) * 18;
				q4[o4] = (byte) h4;
				q4[o4 + 1] = (byte) (h4 >> 8);
				for (int j = 0; j < 16; j++) {
					int lo = Math.min(15, (int) (w[base + j] * id4 + 8.5f));
					int hi = Math.min(15, (int) (w[base + j + 16] * id4 + 8.5f));
					q4[o4 + 2 + j] = (byte) (lo | (hi << 4));
					q4d[base + j] = (lo - 8) * dd4;
					q4d[base + j + 16] = (hi - 8) * dd4;
				}
			}
		}
	}

	/** Re-pack ggml blocks as [scales, padded to sw words][quants, one line a block]. */
	static void split(byte[] blocks, byte[] out, int rows, int nb, int blockBytes, int quantBytes, int sw) {
		int rowBytes = sw * 2 + nb * quantBytes;
		for (int r = 0; r < rows; r++) {
			for (int b = 0; b < nb; b++) {
				int src = (r * nb + b) * blockBytes;
				int dst = r * rowBytes;
				out[dst + b * 2] = blocks[src];
				out[dst + b * 2 + 1] = blocks[src + 1];
				System.arraycopy(blocks, src + 2, out, dst + sw * 2 + b * quantBytes, quantBytes);
			}
		}
	}

}
