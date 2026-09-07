import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.file.*;
import java.util.*;

/**
 * The Q8_0 GEMV that is the CPU kernel's bits, measured ({@code .todo/728},
 * {@code gemv-q8-exact-probe.cu}): the candidate kernels, each pinned BIT FOR BIT against
 * a Java transcription of {@code vec::%matvec-quantized} (the contract of
 * {@code .kb/quantized-matrix.md}: the activation quantized per block in double, four
 * exact integer lane sums a block, four f32 accumulators walked over the blocks in
 * order, folded {@code (acc0 + acc2) + (acc1 + acc3)}), against the shipped
 * {@code gemv_bf16} and {@code gemv-q4-probe.cu}'s fastest Q8_0 shape
 * ({@code gemv_q8_0_dp4}, a warp fold, not the contract's bits) -- device-side kernel
 * time cold from DRAM, at the seven shapes a Qwen3.5-0.8B forward launches, weighted by
 * launch count into ms a forward, the method of {@code Q4KernelProbe}. The activation
 * quantizer ({@code quantize_q8_0}, one launch a call ahead of the GEMV) is timed apart.
 *
 * <pre>
 * cd .todo/artefacts/123-gpu-acceleration
 * nvcc -arch=compute_75 -ptx -fmad=false gemv-q4-probe.cu -o gemv-q4-probe.ptx
 * nvcc -arch=compute_75 -ptx -fmad=false gemv-q8-exact-probe.cu -o gemv-q8-exact-probe.ptx
 * java --enable-native-access=ALL-UNNAMED -Xmx8g Q8ExactKernelProbe.java
 * </pre>
 */
public class Q8ExactKernelProbe {

	/** rows, cols, launches a forward (Qwen3.5-0.8B after .todo/725: 157 in all). */
	static final int[][] SHAPES = { { 248320, 1024, 1 }, { 6144, 1024, 18 }, { 3584, 1024, 48 }, { 1024, 3584, 24 },
			{ 1024, 2048, 24 }, { 2048, 1024, 30 }, { 512, 1024, 12 } };

	/** name, threads a row, x layout (0 natural, 1 the _b8 permutation, 2 the _b4 one). */
	static final Object[][] EXACT = { { "gemv_q8_0_x8", 8, 0 }, { "gemv_q8_0_b8d", 8, 1 },
			{ "gemv_q8_0_b8", 8, 1 } };

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
		MemorySegment shipped = CuLib.module;
		MemorySegment bf16 = CuLib.func("gemv_bf16");
		MemorySegment mod = Arena.global().allocate(CuLib.P);
		CuLib.ck((int) CuLib.cuModuleLoadData.invoke(mod,
				Arena.global().allocateFrom(Files.readString(Path.of("gemv-q4-probe.ptx")))), "cuModuleLoadData");
		CuLib.module = mod.get(CuLib.P, 0);
		MemorySegment dp4 = CuLib.func("gemv_q8_0_dp4");
		CuLib.ck((int) CuLib.cuModuleLoadData.invoke(mod,
				Arena.global().allocateFrom(Files.readString(Path.of("gemv-q8-exact-probe.ptx")))),
				"cuModuleLoadData");
		CuLib.module = mod.get(CuLib.P, 0);
		MemorySegment[] exact = new MemorySegment[EXACT.length];
		for (int i = 0; i < EXACT.length; i++) {
			exact[i] = CuLib.func((String) EXACT[i][0]);
		}
		MemorySegment quantize = CuLib.func("quantize_q8_0");
		CuLib.module = shipped;
		MemorySegment ev = Arena.global().allocate(CuLib.P);
		CuLib.ck((int) cuEventCreate.invoke(ev, 0), "cuEventCreate");
		e0 = ev.get(CuLib.P, 0);
		CuLib.ck((int) cuEventCreate.invoke(ev, 0), "cuEventCreate");
		e1 = ev.get(CuLib.P, 0);

		String[] names = new String[2 + EXACT.length + 1];
		names[0] = "bf16";
		names[1] = "q8_0_dp4";
		for (int i = 0; i < EXACT.length; i++) {
			names[2 + i] = ((String) EXACT[i][0]).substring(5);
		}
		names[names.length - 1] = "quantize";
		double[] perForwardCold = new double[names.length], perForwardBest = new double[names.length];
		for (int[] s : SHAPES) {
			int rows = s[0], cols = s[1], launches = s[2];
			int nb = cols / 32;
			long n = (long) rows * cols;
			float[] wf = new float[(int) n], x = new float[cols];
			for (int i = 0; i < n; i++) {
				wf[i] = (float) Math.sin(i * 0.37) * (1 + 0.5f * (float) Math.cos(i * 0.0013));
			}
			for (int j = 0; j < cols; j++) {
				x[j] = (float) Math.cos(j * 0.11) * (1 + 0.3f * (float) Math.sin(j * 0.017));
			}
			short[] wb = new short[(int) n];
			for (int i = 0; i < n; i++) {
				wb[i] = bf16(wf[i]);
			}
			byte[] q8b = new byte[(int) (rows * (long) nb * 34)];
			quantizeQ8(wf, rows, cols, q8b);
			// The dp4 probe's x: [nb f32 scales, padded][cols int8].
			byte[] xq = new byte[cols];
			double[] xs = new double[nb];
			quantizeX(x, xq, xs);
			int xpad = (nb * 4 + 15) & ~15;
			byte[] xpackDp = new byte[xpad + cols];
			java.nio.ByteBuffer fb = java.nio.ByteBuffer.wrap(xpackDp).order(java.nio.ByteOrder.LITTLE_ENDIAN);
			for (int b = 0; b < nb; b++) {
				fb.putFloat(b * 4, (float) xs[b]);
			}
			System.arraycopy(xq, 0, xpackDp, xpad, cols);
			// The contract's x, as the device quantizer must write it: [nb f64 sx][cols int8].
			byte[] xpackExact = new byte[nb * 8 + cols];
			java.nio.ByteBuffer db = java.nio.ByteBuffer.wrap(xpackExact).order(java.nio.ByteOrder.LITTLE_ENDIAN);
			for (int b = 0; b < nb; b++) {
				db.putDouble(b * 8, xs[b]);
			}
			System.arraycopy(xq, 0, xpackExact, nb * 8, cols);
			// The one-lane-a-thread layouts: _b8 (word t of a block = thread 4h + i's
			// columns 4k + i, k in [4h, 4h + 4)) and _b4 (words 2i, 2i + 1 = lane i's
			// eight columns 4m + i).
			byte[] xpackB8 = xpackExact.clone(), xpackB4 = xpackExact.clone();
			for (int b = 0; b < nb; b++) {
				for (int t = 0; t < 8; t++) {
					int i = t & 3, h = t >> 2;
					for (int m = 0; m < 4; m++) {
						xpackB8[nb * 8 + b * 32 + 4 * t + m] = xq[b * 32 + 4 * (4 * h + m) + i];
					}
				}
				for (int i = 0; i < 4; i++) {
					for (int m = 0; m < 8; m++) {
						xpackB4[nb * 8 + b * 32 + 8 * i + m] = xq[b * 32 + 4 * m + i];
					}
				}
			}
			float[] contract = contract(q8b, xq, xs, rows, cols);

			long bf16Bytes = n * 2;
			int copies = (int) Math.max(1, Math.min(512, (COLD_BYTES + bf16Bytes - 1) / bf16Bytes));
			System.out.printf("%n== %dx%d, x%d a forward: bf16 %.2f MB, q8_0 %.2f MB, %d copies%n", rows, cols,
					launches, bf16Bytes / 1e6, q8b.length / 1e6, copies);
			System.out.printf("%-12s %10s %10s %10s %10s   %10s %10s %s%n", "kernel", "cold med", "cold min",
					"hot med", "hot min", "GB/s cold", "GB/s hot", "rows off the contract's bits");
			try (Arena arena = Arena.ofConfined()) {
				long dx = CuLib.alloc(arena, (long) cols * 4, true), dy = CuLib.alloc(arena, (long) rows * 4, true);
				CuLib.ck((int) CuLib.cuMemcpyHtoDc.invoke(dx, MemorySegment.ofArray(x), (long) cols * 4), "htod");
				long dxDp = CuLib.alloc(arena, xpackDp.length, true);
				CuLib.ck((int) CuLib.cuMemcpyHtoDc.invoke(dxDp, MemorySegment.ofArray(xpackDp), xpackDp.length),
						"htod");
				// The device quantizer writes the exact kernels' x; checked against the
				// host transcription byte for byte, then timed.
				long dxExact = CuLib.alloc(arena, xpackExact.length, true);
				long dxB8 = CuLib.alloc(arena, xpackB8.length, true), dxB4 = CuLib.alloc(arena, xpackB4.length, true);
				CuLib.ck((int) CuLib.cuMemcpyHtoDc.invoke(dxB8, MemorySegment.ofArray(xpackB8), xpackB8.length),
						"htod");
				CuLib.ck((int) CuLib.cuMemcpyHtoDc.invoke(dxB4, MemorySegment.ofArray(xpackB4), xpackB4.length),
						"htod");
				launchQuantize(quantize, dx, dxExact, cols, arena);
				CuLib.ck((int) CuLib.cuCtxSynchronize.invoke(), "sync");
				byte[] back = new byte[xpackExact.length];
				CuLib.ck((int) CuLib.cuMemcpyDtoHc.invoke(MemorySegment.ofArray(back), dxExact, back.length),
						"dtoh");
				if (!Arrays.equals(back, xpackExact)) {
					System.out.println("quantize_q8_0 DIFFERS from the host transcription");
					for (int i = 0; i < back.length; i++) {
						if (back[i] != xpackExact[i]) {
							System.out.printf("  byte %d: device %d host %d%n", i, back[i], xpackExact[i]);
							break;
						}
					}
				}
				int k = 0;
				k = one(k, "bf16", bf16, 32, MemorySegment.ofArray(wb), n * 2, copies, dx, dy, rows, cols,
						launches, null, perForwardCold, perForwardBest, arena);
				k = one(k, "q8_0_dp4", dp4, 32, MemorySegment.ofArray(q8b), q8b.length, copies, dxDp, dy, rows,
						cols, launches, null, perForwardCold, perForwardBest, arena);
				for (int i = 0; i < EXACT.length; i++) {
					int layout = (Integer) EXACT[i][2];
					long dX = layout == 0 ? dxExact : layout == 1 ? dxB8 : dxB4;
					k = one(k, names[2 + i], exact[i], (Integer) EXACT[i][1], MemorySegment.ofArray(q8b),
							q8b.length, copies, dX, dy, rows, cols, launches, contract, perForwardCold,
							perForwardBest, arena);
				}
				// The device-side cost of quantizing in a launch of its own AHEAD of the
				// GEMV, back to back on the stream: the pair's hot time less the GEMV's.
				try (Arena a2 = Arena.ofConfined()) {
					long dw = CuLib.alloc(a2, q8b.length, true);
					CuLib.ck((int) CuLib.cuMemcpyHtoDc.invoke(dw, MemorySegment.ofArray(q8b), q8b.length), "htod");
					for (int i = 0; i < EXACT.length; i++) {
						if ((Integer) EXACT[i][2] != 0) {
							continue;
						}
						double[] alone = new double[40], pair = new double[40];
						for (int r = 0; r < 40; r++) {
							alone[r] = timed(exact[i], (Integer) EXACT[i][1], dw, dxExact, dy, rows, cols);
							pair[r] = timedPair(quantize, exact[i], (Integer) EXACT[i][1], dw, dx, dxExact, dy,
									rows, cols);
						}
						Arrays.sort(alone);
						Arrays.sort(pair);
						System.out.printf("%-12s quantize+gemv back to back, hot: %.1f against %.1f alone (+%.1f us)%n",
								names[2 + i], pair[20], alone[20], pair[20] - alone[20]);
					}
					CuLib.free(dw, true);
				}
				// The quantizer alone, hot (x is always hot: it was just written).
				double[] tq = new double[60];
				for (int r = 0; r < tq.length; r++) {
					tq[r] = timedQuantize(quantize, dx, dxExact, cols);
				}
				Arrays.sort(tq);
				System.out.printf("%-12s %10s %10s %10.1f %10.1f%n", "quantize", "-", "-", tq[tq.length / 2], tq[0]);
				perForwardCold[k] += launches * tq[tq.length / 2] / 1e3;
				perForwardBest[k] += launches * tq[0] / 1e3;
				CuLib.free(dxExact, true);
				CuLib.free(dxB8, true);
				CuLib.free(dxB4, true);
				CuLib.free(dxDp, true);
				CuLib.free(dx, true);
				CuLib.free(dy, true);
			}
		}
		System.out.printf("%n== kernel time a FORWARD PASS (157 launches), ms: sum over shapes of count x median%n");
		System.out.printf("%-12s %10s %10s%n", "kernel", "cold med", "cold min");
		for (int i = 0; i < names.length; i++) {
			System.out.printf("%-12s %10.3f %10.3f%n", names[i], perForwardCold[i], perForwardBest[i]);
		}
	}

	static int one(int k, String name, MemorySegment kernel, int threadsPerRow, MemorySegment host, long bytes,
			int copies, long dx, long dy, int rows, int cols, int launches, float[] contract, double[] cold,
			double[] best, Arena arena) throws Throwable {
		long[] d = new long[copies];
		for (int c = 0; c < copies; c++) {
			d[c] = CuLib.alloc(arena, bytes, true);
			CuLib.ck((int) CuLib.cuMemcpyHtoDc.invoke(d[c], host, bytes), "htod");
		}
		CuLib.ck((int) CuLib.cuCtxSynchronize.invoke(), "sync");
		for (int i = 0; i < 10; i++) {
			timed(kernel, threadsPerRow, d[i % copies], dx, dy, rows, cols);
		}
		int reps = Math.max(copies, 40);
		double[] tc = new double[reps];
		for (int r = 0; r < reps; r++) {
			tc[r] = timed(kernel, threadsPerRow, d[r % copies], dx, dy, rows, cols);
		}
		double[] th = new double[40];
		for (int r = 0; r < th.length; r++) {
			th[r] = timed(kernel, threadsPerRow, d[0], dx, dy, rows, cols);
		}
		float[] y = new float[rows];
		CuLib.ck((int) CuLib.cuMemcpyDtoHc.invoke(MemorySegment.ofArray(y), dy, (long) rows * 4), "dtoh");
		String check = "-";
		if (contract != null) {
			int off = 0;
			int firstOff = -1;
			for (int r = 0; r < rows; r++) {
				if (Float.floatToRawIntBits(y[r]) != Float.floatToRawIntBits(contract[r])) {
					off++;
					if (firstOff < 0) {
						firstOff = r;
					}
				}
			}
			check = off == 0 ? "0 (bit-identical)"
					: off + " <-- WRONG, first row " + firstOff + ": " + y[firstOff] + " vs " + contract[firstOff];
		}
		Arrays.sort(tc);
		Arrays.sort(th);
		double cm = tc[tc.length / 2], hm = th[th.length / 2];
		System.out.printf("%-12s %10.1f %10.1f %10.1f %10.1f   %10.0f %10.0f   %s%n", name, cm, tc[0], hm, th[0],
				bytes / cm / 1e3, bytes / hm / 1e3, check);
		cold[k] += launches * cm / 1e3;
		best[k] += launches * tc[0] / 1e3;
		for (int c = 0; c < copies; c++) {
			CuLib.free(d[c], true);
		}
		return k + 1;
	}

	static double timed(MemorySegment kernel, int threadsPerRow, long dw, long dx, long dy, int rows, int cols)
			throws Throwable {
		try (Arena a = Arena.ofConfined()) {
			CuLib.ck((int) cuEventRecord.invoke(e0, MemorySegment.NULL), "record");
			launch(kernel, threadsPerRow, dw, dx, dy, rows, cols, a);
			CuLib.ck((int) cuEventRecord.invoke(e1, MemorySegment.NULL), "record");
			CuLib.ck((int) cuEventSynchronize.invoke(e1), "event sync");
			MemorySegment ms = a.allocate(ValueLayout.JAVA_FLOAT);
			CuLib.ck((int) cuEventElapsedTime.invoke(ms, e0, e1), "elapsed");
			return ms.get(ValueLayout.JAVA_FLOAT, 0) * 1e3;
		}
	}

	static double timedPair(MemorySegment quantize, MemorySegment kernel, int threadsPerRow, long dw, long dx,
			long dX, long dy, int rows, int cols) throws Throwable {
		try (Arena a = Arena.ofConfined()) {
			CuLib.ck((int) cuEventRecord.invoke(e0, MemorySegment.NULL), "record");
			launchQuantize(quantize, dx, dX, cols, a);
			launch(kernel, threadsPerRow, dw, dX, dy, rows, cols, a);
			CuLib.ck((int) cuEventRecord.invoke(e1, MemorySegment.NULL), "record");
			CuLib.ck((int) cuEventSynchronize.invoke(e1), "event sync");
			MemorySegment ms = a.allocate(ValueLayout.JAVA_FLOAT);
			CuLib.ck((int) cuEventElapsedTime.invoke(ms, e0, e1), "elapsed");
			return ms.get(ValueLayout.JAVA_FLOAT, 0) * 1e3;
		}
	}

	static double timedQuantize(MemorySegment kernel, long dx, long dX, int cols) throws Throwable {
		try (Arena a = Arena.ofConfined()) {
			CuLib.ck((int) cuEventRecord.invoke(e0, MemorySegment.NULL), "record");
			launchQuantize(kernel, dx, dX, cols, a);
			CuLib.ck((int) cuEventRecord.invoke(e1, MemorySegment.NULL), "record");
			CuLib.ck((int) cuEventSynchronize.invoke(e1), "event sync");
			MemorySegment ms = a.allocate(ValueLayout.JAVA_FLOAT);
			CuLib.ck((int) cuEventElapsedTime.invoke(ms, e0, e1), "elapsed");
			return ms.get(ValueLayout.JAVA_FLOAT, 0) * 1e3;
		}
	}

	static void launch(MemorySegment kernel, int threadsPerRow, long dw, long dx, long dy, int rows, int cols,
			Arena arena) throws Throwable {
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
		int block = 256, rowsPerBlock = block / threadsPerRow;
		CuLib.ck((int) CuLib.cuLaunchKernel.invoke(kernel, (rows + rowsPerBlock - 1) / rowsPerBlock, 1, 1, block, 1,
				1, 0, MemorySegment.NULL, params, MemorySegment.NULL), "launch");
	}

	static void launchQuantize(MemorySegment kernel, long dx, long dX, int cols, Arena arena) throws Throwable {
		MemorySegment px = arena.allocate(CuLib.L), pX = arena.allocate(CuLib.L);
		px.set(CuLib.L, 0, dx);
		pX.set(CuLib.L, 0, dX);
		MemorySegment pc = arena.allocate(CuLib.I);
		pc.set(CuLib.I, 0, cols);
		MemorySegment params = arena.allocate(CuLib.P, 3);
		params.setAtIndex(CuLib.P, 0, px);
		params.setAtIndex(CuLib.P, 1, pX);
		params.setAtIndex(CuLib.P, 2, pc);
		int nb = cols / 32, block = 256, blocksPerBlock = block / 32;
		CuLib.ck((int) CuLib.cuLaunchKernel.invoke(kernel, (nb + blocksPerBlock - 1) / blocksPerBlock, 1, 1, block,
				1, 1, 0, MemorySegment.NULL, params, MemorySegment.NULL), "launch");
	}

	static short bf16(float value) {
		int bits = Float.floatToRawIntBits(value);
		return (short) ((bits + 0x7fff + ((bits >>> 16) & 1)) >>> 16);
	}

	/**
	 * The CPU contract transcribed ({@code VecSimdKernels.matvecQ8F}, and the defun): per
	 * row, four f32 accumulators over the blocks in order, each lane sum exact, the scale
	 * product in double narrowed once, no FMA, the fold (acc0 + acc2) + (acc1 + acc3).
	 */
	static float[] contract(byte[] blocks, byte[] xq, double[] xs, int rows, int cols) {
		int nb = cols / 32;
		float[] y = new float[rows];
		for (int r = 0; r < rows; r++) {
			float a0 = 0, a1 = 0, a2 = 0, a3 = 0;
			for (int b = 0; b < nb; b++) {
				int o = (r * nb + b) * 34;
				double sw = Float.float16ToFloat((short) ((blocks[o] & 0xFF) | (blocks[o + 1] << 8)));
				float p = (float) (sw * xs[b]);
				int s0 = 0, s1 = 0, s2 = 0, s3 = 0;
				for (int k = 0; k < 8; k++) {
					int j = b * 32 + 4 * k;
					s0 += blocks[o + 2 + 4 * k] * xq[j];
					s1 += blocks[o + 3 + 4 * k] * xq[j + 1];
					s2 += blocks[o + 4 + 4 * k] * xq[j + 2];
					s3 += blocks[o + 5 + 4 * k] * xq[j + 3];
				}
				a0 = a0 + (float) s0 * p;
				a1 = a1 + (float) s1 * p;
				a2 = a2 + (float) s2 * p;
				a3 = a3 + (float) s3 * p;
			}
			y[r] = (a0 + a2) + (a1 + a3);
		}
		return y;
	}

	/** The CPU contract's activation quantizer: amax / 127 in double, CL round = rint. */
	static void quantizeX(float[] x, byte[] xq, double[] xs) {
		for (int b = 0; b < xs.length; b++) {
			double amax = 0;
			for (int i = 0; i < 32; i++) {
				double v = Math.abs((double) x[b * 32 + i]);
				if (v > amax) {
					amax = v;
				}
			}
			double sx = amax / 127.0;
			xs[b] = sx;
			for (int i = 0; i < 32; i++) {
				xq[b * 32 + i] = (byte) (sx == 0 ? 0 : (int) Math.rint(x[b * 32 + i] / sx));
			}
		}
	}

	/** ggml's quantize_row_q8_0_ref. */
	static void quantizeQ8(float[] w, int rows, int cols, byte[] q8) {
		int nb = cols / 32;
		for (int r = 0; r < rows; r++) {
			for (int b = 0; b < nb; b++) {
				int base = r * cols + b * 32;
				float amax = 0;
				for (int i = 0; i < 32; i++) {
					float v = Math.abs(w[base + i]);
					if (v > amax) {
						amax = v;
					}
				}
				float d8 = amax / 127f, id8 = d8 != 0 ? 1f / d8 : 0f;
				short h8 = Float.floatToFloat16(d8);
				int o8 = (r * nb + b) * 34;
				q8[o8] = (byte) h8;
				q8[o8 + 1] = (byte) (h8 >> 8);
				for (int i = 0; i < 32; i++) {
					float v = w[base + i] * id8;
					int q = v < 0 ? -Math.round(-v) : Math.round(v);
					q8[o8 + 2 + i] = (byte) q;
				}
			}
		}
	}

}
