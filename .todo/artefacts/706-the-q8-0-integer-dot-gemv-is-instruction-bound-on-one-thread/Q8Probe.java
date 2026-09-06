package am.ik.rontolisp.eval;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.ShortVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorShuffle;
import jdk.incubator.vector.VectorSpecies;

/**
 * Candidate block-dot shapes for the Q8_0 GEMV, timed one thread against the shipped
 * kernel.
 */
public final class Q8Probe {

	static final VectorSpecies<Byte> B64 = ByteVector.SPECIES_64;

	static final VectorSpecies<Byte> B128 = ByteVector.SPECIES_128;

	static final VectorSpecies<Short> S64 = ShortVector.SPECIES_64;

	static final VectorSpecies<Short> S128 = ShortVector.SPECIES_128;

	static final VectorSpecies<Integer> I128 = IntVector.SPECIES_128;

	static final VectorSpecies<Float> F128 = FloatVector.SPECIES_128;

	static final VectorShuffle<Short> SWAP_HALVES = VectorShuffle.fromValues(S128, 4, 5, 6, 7, 0, 1, 2, 3);

	static final VectorShuffle<Byte> INTERLEAVE = VectorShuffle.fromValues(B64, 0, 4, 1, 5, 2, 6, 3, 7);

	interface Variant {

		void run();

	}

	static long[] time(Variant v, int iterations, int rounds) {
		for (int i = 0; i < 8; i++) {
			v.run();
		}
		long[] samples = new long[rounds];
		for (int round = 0; round < rounds; round++) {
			long start = System.nanoTime();
			for (int i = 0; i < iterations; i++) {
				v.run();
			}
			samples[round] = (System.nanoTime() - start) / iterations;
		}
		Arrays.sort(samples);
		return samples;
	}

	static ShortVector s8(byte[] a, int o) {
		return (ShortVector) ByteVector.fromArray(B64, a, o).convertShape(VectorOperators.B2S, S128, 0);
	}

	static ShortVector x8(short[] a, int o) {
		return ShortVector.fromArray(S128, a, o);
	}

	static IntVector lo(ShortVector p) {
		return (IntVector) p.convertShape(VectorOperators.S2I, I128, 0);
	}

	static IntVector hi(ShortVector p) {
		return (IntVector) p.convertShape(VectorOperators.S2I, I128, 1);
	}

	// --- B: 64-bit byte loads, one expanding B2S each; S2I part 0 / 1
	static IntVector dotB(byte[] w, int wo, byte[] xq, int xo) {
		ShortVector p = s8(w, wo).mul(s8(xq, xo)).add(s8(w, wo + 8).mul(s8(xq, xo + 8)));
		ShortVector q = s8(w, wo + 16).mul(s8(xq, xo + 16)).add(s8(w, wo + 24).mul(s8(xq, xo + 24)));
		return lo(p).add(hi(p)).add(lo(q)).add(hi(q));
	}

	// --- Bx: B with the activation pre-widened to shorts
	static IntVector dotBx(byte[] w, int wo, short[] xh, int xo) {
		ShortVector p = s8(w, wo).mul(x8(xh, xo)).add(s8(w, wo + 8).mul(x8(xh, xo + 8)));
		ShortVector q = s8(w, wo + 16).mul(x8(xh, xo + 16)).add(s8(w, wo + 24).mul(x8(xh, xo + 24)));
		return lo(p).add(hi(p)).add(lo(q)).add(hi(q));
	}

	// --- Bs: Bx with the upper half brought down by a constant rearrange, then part 0
	static IntVector dotBs(byte[] w, int wo, short[] xh, int xo) {
		ShortVector p = s8(w, wo).mul(x8(xh, xo)).add(s8(w, wo + 8).mul(x8(xh, xo + 8)));
		ShortVector q = s8(w, wo + 16).mul(x8(xh, xo + 16)).add(s8(w, wo + 24).mul(x8(xh, xo + 24)));
		return lo(p).add(lo(p.rearrange(SWAP_HALVES))).add(lo(q)).add(lo(q.rearrange(SWAP_HALVES)));
	}

	// --- G: loads at c and c+4, only the low four short lanes widened (needs 4 bytes of
	// slack after the block: the probe pads the matrix); unrolled
	static IntVector dotG(byte[] w, int wo, short[] xh, int xo) {
		ShortVector p0 = s8(w, wo).mul(x8(xh, xo)).add(s8(w, wo + 4).mul(x8(xh, xo + 4)));
		ShortVector p1 = s8(w, wo + 8).mul(x8(xh, xo + 8)).add(s8(w, wo + 12).mul(x8(xh, xo + 12)));
		ShortVector p2 = s8(w, wo + 16).mul(x8(xh, xo + 16)).add(s8(w, wo + 20).mul(x8(xh, xo + 20)));
		ShortVector p3 = s8(w, wo + 24).mul(x8(xh, xo + 24)).add(s8(w, wo + 28).mul(x8(xh, xo + 28)));
		return lo(p0).add(lo(p1)).add(lo(p2)).add(lo(p3));
	}

	// --- H: G for the first sixteen columns (in-bounds), B for the last sixteen
	static IntVector dotH(byte[] w, int wo, short[] xh, int xo) {
		ShortVector p0 = s8(w, wo).mul(x8(xh, xo)).add(s8(w, wo + 4).mul(x8(xh, xo + 4)));
		ShortVector p1 = s8(w, wo + 8).mul(x8(xh, xo + 8)).add(s8(w, wo + 12).mul(x8(xh, xo + 12)));
		ShortVector q = s8(w, wo + 16).mul(x8(xh, xo + 16)).add(s8(w, wo + 24).mul(x8(xh, xo + 24)));
		return lo(p0).add(lo(p1)).add(lo(q)).add(hi(q));
	}

	// --- Hs: H with the one part-1 widen replaced by the rearrange
	static IntVector dotHs(byte[] w, int wo, short[] xh, int xo) {
		ShortVector p0 = s8(w, wo).mul(x8(xh, xo)).add(s8(w, wo + 4).mul(x8(xh, xo + 4)));
		ShortVector p1 = s8(w, wo + 8).mul(x8(xh, xo + 8)).add(s8(w, wo + 12).mul(x8(xh, xo + 12)));
		ShortVector q = s8(w, wo + 16).mul(x8(xh, xo + 16)).add(s8(w, wo + 24).mul(x8(xh, xo + 24)));
		return lo(p0).add(lo(p1)).add(lo(q)).add(lo(q.rearrange(SWAP_HALVES)));
	}

	// --- E: 64-bit short species throughout (loads at c and c+4; padded); unrolled
	static ShortVector s4(byte[] a, int o) {
		return (ShortVector) ByteVector.fromArray(B64, a, o).convertShape(VectorOperators.B2S, S64, 0);
	}

	static ShortVector x4(short[] a, int o) {
		return ShortVector.fromArray(S64, a, o);
	}

	static IntVector dotE(byte[] w, int wo, short[] xh, int xo) {
		ShortVector p0 = s4(w, wo).mul(x4(xh, xo)).add(s4(w, wo + 4).mul(x4(xh, xo + 4)));
		ShortVector p1 = s4(w, wo + 8).mul(x4(xh, xo + 8)).add(s4(w, wo + 12).mul(x4(xh, xo + 12)));
		ShortVector p2 = s4(w, wo + 16).mul(x4(xh, xo + 16)).add(s4(w, wo + 20).mul(x4(xh, xo + 20)));
		ShortVector p3 = s4(w, wo + 24).mul(x4(xh, xo + 24)).add(s4(w, wo + 28).mul(x4(xh, xo + 28)));
		return lo(p0).add(lo(p1)).add(lo(p2)).add(lo(p3));
	}

	// --- Q: the weight bytes interleaved on load (lanes 0,4,1,5,2,6,3,7) against an
	// activation permuted the same way once, so the reinterpret split keeps lane i =
	// columns j mod 4 = i
	static ShortVector s8i(byte[] a, int o) {
		return (ShortVector) ByteVector.fromArray(B64, a, o).rearrange(INTERLEAVE).convertShape(VectorOperators.B2S, S128, 0);
	}

	static IntVector split(ShortVector p) {
		IntVector pi = p.reinterpretAsInts();
		return pi.lanewise(VectorOperators.LSHL, 16).lanewise(VectorOperators.ASHR, 16).add(pi.lanewise(VectorOperators.ASHR, 16));
	}

	static IntVector dotQ(byte[] w, int wo, short[] xh, int xo) {
		ShortVector p = s8i(w, wo).mul(x8(xh, xo)).add(s8i(w, wo + 8).mul(x8(xh, xo + 8)));
		ShortVector q = s8i(w, wo + 16).mul(x8(xh, xo + 16)).add(s8i(w, wo + 24).mul(x8(xh, xo + 24)));
		return split(p).add(split(q));
	}

	// --- R: the reinterpret split without the permute (lane i = short lanes 2i, 2i+1: a
	// DIFFERENT lane definition, a ceiling probe only)
	static IntVector dotR(byte[] w, int wo, short[] xh, int xo) {
		ShortVector p = s8(w, wo).mul(x8(xh, xo)).add(s8(w, wo + 8).mul(x8(xh, xo + 8)));
		ShortVector q = s8(w, wo + 16).mul(x8(xh, xo + 16)).add(s8(w, wo + 24).mul(x8(xh, xo + 24)));
		return split(p).add(split(q));
	}

	// --- rows: one small method per shape

	static float finish(FloatVector acc) {
		return (acc.lane(0) + acc.lane(2)) + (acc.lane(1) + acc.lane(3));
	}

	static FloatVector scale(byte[] w, int bo, double sx) {
		return FloatVector.broadcast(F128, (float) (VecSimdKernels.q8Scale(w, bo) * sx));
	}

	static FloatVector step(FloatVector acc, IntVector isum, byte[] w, int bo, double sx) {
		return acc.add(((FloatVector) isum.convert(VectorOperators.I2F, 0)).mul(scale(w, bo, sx)));
	}

	static float rowB(byte[] w, int base, int nb, byte[] xq, double[] xs) {
		FloatVector acc = FloatVector.zero(F128);
		for (int b = 0; b < nb; b++) {
			int bo = base + b * 34;
			acc = step(acc, dotB(w, bo + 2, xq, b * 32), w, bo, xs[b]);
		}
		return finish(acc);
	}

	static float rowBx(byte[] w, int base, int nb, short[] xh, double[] xs) {
		FloatVector acc = FloatVector.zero(F128);
		for (int b = 0; b < nb; b++) {
			int bo = base + b * 34;
			acc = step(acc, dotBx(w, bo + 2, xh, b * 32), w, bo, xs[b]);
		}
		return finish(acc);
	}

	/** Bx's integer work alone: the lane sums summed as ints, no scale (timing only). */
	static float rowBxInt(byte[] w, int base, int nb, short[] xh, double[] xs) {
		IntVector acc = IntVector.zero(I128);
		for (int b = 0; b < nb; b++) {
			int bo = base + b * 34;
			acc = acc.add(dotBx(w, bo + 2, xh, b * 32));
		}
		return acc.reduceLanes(VectorOperators.ADD);
	}

	/** The scale work alone: no dot (timing only). */
	static float rowScaleOnly(byte[] w, int base, int nb, short[] xh, double[] xs) {
		FloatVector acc = FloatVector.zero(F128);
		for (int b = 0; b < nb; b++) {
			int bo = base + b * 34;
			acc = acc.add(scale(w, bo, xs[b]));
		}
		return finish(acc);
	}

	static float rowBs(byte[] w, int base, int nb, short[] xh, double[] xs) {
		FloatVector acc = FloatVector.zero(F128);
		for (int b = 0; b < nb; b++) {
			int bo = base + b * 34;
			acc = step(acc, dotBs(w, bo + 2, xh, b * 32), w, bo, xs[b]);
		}
		return finish(acc);
	}

	static float rowG(byte[] w, int base, int nb, short[] xh, double[] xs) {
		FloatVector acc = FloatVector.zero(F128);
		for (int b = 0; b < nb; b++) {
			int bo = base + b * 34;
			acc = step(acc, dotG(w, bo + 2, xh, b * 32), w, bo, xs[b]);
		}
		return finish(acc);
	}

	static float rowH(byte[] w, int base, int nb, short[] xh, double[] xs) {
		FloatVector acc = FloatVector.zero(F128);
		for (int b = 0; b < nb; b++) {
			int bo = base + b * 34;
			acc = step(acc, dotH(w, bo + 2, xh, b * 32), w, bo, xs[b]);
		}
		return finish(acc);
	}

	static float rowHs(byte[] w, int base, int nb, short[] xh, double[] xs) {
		FloatVector acc = FloatVector.zero(F128);
		for (int b = 0; b < nb; b++) {
			int bo = base + b * 34;
			acc = step(acc, dotHs(w, bo + 2, xh, b * 32), w, bo, xs[b]);
		}
		return finish(acc);
	}

	static float rowE(byte[] w, int base, int nb, short[] xh, double[] xs) {
		FloatVector acc = FloatVector.zero(F128);
		for (int b = 0; b < nb; b++) {
			int bo = base + b * 34;
			acc = step(acc, dotE(w, bo + 2, xh, b * 32), w, bo, xs[b]);
		}
		return finish(acc);
	}

	static float rowQ(byte[] w, int base, int nb, short[] xh, double[] xs) {
		FloatVector acc = FloatVector.zero(F128);
		for (int b = 0; b < nb; b++) {
			int bo = base + b * 34;
			acc = step(acc, dotQ(w, bo + 2, xh, b * 32), w, bo, xs[b]);
		}
		return finish(acc);
	}

	static float rowR(byte[] w, int base, int nb, short[] xh, double[] xs) {
		FloatVector acc = FloatVector.zero(F128);
		for (int b = 0; b < nb; b++) {
			int bo = base + b * 34;
			acc = step(acc, dotR(w, bo + 2, xh, b * 32), w, bo, xs[b]);
		}
		return finish(acc);
	}

	/** Two rows a pass over Bx, the activation loads shared. */
	static void rows2Bx(float[] r, int row, byte[] w, int base, int rowBytes, int nb, short[] xh, double[] xs) {
		FloatVector acc0 = FloatVector.zero(F128);
		FloatVector acc1 = FloatVector.zero(F128);
		int base1 = base + rowBytes;
		for (int b = 0; b < nb; b++) {
			int bo = base + b * 34;
			int bo1 = base1 + b * 34;
			int xo = b * 32;
			ShortVector xa = x8(xh, xo);
			ShortVector xb = x8(xh, xo + 8);
			ShortVector xc = x8(xh, xo + 16);
			ShortVector xd = x8(xh, xo + 24);
			ShortVector p = s8(w, bo + 2).mul(xa).add(s8(w, bo + 10).mul(xb));
			ShortVector q = s8(w, bo + 18).mul(xc).add(s8(w, bo + 26).mul(xd));
			ShortVector p1 = s8(w, bo1 + 2).mul(xa).add(s8(w, bo1 + 10).mul(xb));
			ShortVector q1 = s8(w, bo1 + 18).mul(xc).add(s8(w, bo1 + 26).mul(xd));
			acc0 = step(acc0, lo(p).add(hi(p)).add(lo(q)).add(hi(q)), w, bo, xs[b]);
			acc1 = step(acc1, lo(p1).add(hi(p1)).add(lo(q1)).add(hi(q1)), w, bo1, xs[b]);
		}
		r[row] = finish(acc0);
		r[row + 1] = finish(acc1);
	}

	static final java.lang.invoke.VarHandle SHORT_LE = java.lang.invoke.MethodHandles
		.byteArrayViewVarHandle(short[].class, java.nio.ByteOrder.LITTLE_ENDIAN);

	static FloatVector scaleVh(byte[] w, int bo, double sx) {
		return FloatVector.broadcast(F128, (float) ((double) Float.float16ToFloat((short) SHORT_LE.get(w, bo)) * sx));
	}

	/** Bs with the scale's two bytes read as one little-endian short. */
	static float rowBsVh(byte[] w, int base, int nb, short[] xh, double[] xs) {
		FloatVector acc = FloatVector.zero(F128);
		for (int b = 0; b < nb; b++) {
			int bo = base + b * 34;
			acc = acc.add(((FloatVector) dotBs(w, bo + 2, xh, b * 32).convert(VectorOperators.I2F, 0))
				.mul(scaleVh(w, bo, xs[b])));
		}
		return finish(acc);
	}

	/** Bs, two blocks an iteration (nb is even at every shape measured). */
	static float rowBsU2(byte[] w, int base, int nb, short[] xh, double[] xs) {
		FloatVector acc = FloatVector.zero(F128);
		for (int b = 0; b < nb; b += 2) {
			int bo = base + b * 34;
			acc = step(acc, dotBs(w, bo + 2, xh, b * 32), w, bo, xs[b]);
			acc = step(acc, dotBs(w, bo + 36, xh, b * 32 + 32), w, bo + 34, xs[b + 1]);
		}
		return finish(acc);
	}

	/** Two rows a pass over Bs (the rearrange split), the activation loads shared. */
	static void rows2Bs(float[] r, int row, byte[] w, int base, int rowBytes, int nb, short[] xh, double[] xs) {
		FloatVector acc0 = FloatVector.zero(F128);
		FloatVector acc1 = FloatVector.zero(F128);
		int base1 = base + rowBytes;
		for (int b = 0; b < nb; b++) {
			int bo = base + b * 34;
			int bo1 = base1 + b * 34;
			int xo = b * 32;
			ShortVector xa = x8(xh, xo);
			ShortVector xb = x8(xh, xo + 8);
			ShortVector xc = x8(xh, xo + 16);
			ShortVector xd = x8(xh, xo + 24);
			ShortVector p = s8(w, bo + 2).mul(xa).add(s8(w, bo + 10).mul(xb));
			ShortVector q = s8(w, bo + 18).mul(xc).add(s8(w, bo + 26).mul(xd));
			ShortVector p1 = s8(w, bo1 + 2).mul(xa).add(s8(w, bo1 + 10).mul(xb));
			ShortVector q1 = s8(w, bo1 + 18).mul(xc).add(s8(w, bo1 + 26).mul(xd));
			acc0 = step(acc0, lo(p).add(lo(p.rearrange(SWAP_HALVES))).add(lo(q)).add(lo(q.rearrange(SWAP_HALVES))),
					w, bo, xs[b]);
			acc1 = step(acc1,
					lo(p1).add(lo(p1.rearrange(SWAP_HALVES))).add(lo(q1)).add(lo(q1.rearrange(SWAP_HALVES))), w, bo1,
					xs[b]);
		}
		r[row] = finish(acc0);
		r[row + 1] = finish(acc1);
	}

	/** Four rows a pass over Bs. */
	static void rows4Bs(float[] r, int row, byte[] w, int base, int rowBytes, int nb, short[] xh, double[] xs) {
		FloatVector acc0 = FloatVector.zero(F128);
		FloatVector acc1 = FloatVector.zero(F128);
		FloatVector acc2 = FloatVector.zero(F128);
		FloatVector acc3 = FloatVector.zero(F128);
		int base1 = base + rowBytes;
		int base2 = base1 + rowBytes;
		int base3 = base2 + rowBytes;
		for (int b = 0; b < nb; b++) {
			int bb = b * 34;
			int bo = base + bb;
			int bo1 = base1 + bb;
			int bo2 = base2 + bb;
			int bo3 = base3 + bb;
			int xo = b * 32;
			ShortVector xa = x8(xh, xo);
			ShortVector xb = x8(xh, xo + 8);
			ShortVector xc = x8(xh, xo + 16);
			ShortVector xd = x8(xh, xo + 24);
			double sx = xs[b];
			ShortVector p = s8(w, bo + 2).mul(xa).add(s8(w, bo + 10).mul(xb));
			ShortVector q = s8(w, bo + 18).mul(xc).add(s8(w, bo + 26).mul(xd));
			acc0 = step(acc0, lo(p).add(lo(p.rearrange(SWAP_HALVES))).add(lo(q)).add(lo(q.rearrange(SWAP_HALVES))),
					w, bo, sx);
			p = s8(w, bo1 + 2).mul(xa).add(s8(w, bo1 + 10).mul(xb));
			q = s8(w, bo1 + 18).mul(xc).add(s8(w, bo1 + 26).mul(xd));
			acc1 = step(acc1, lo(p).add(lo(p.rearrange(SWAP_HALVES))).add(lo(q)).add(lo(q.rearrange(SWAP_HALVES))),
					w, bo1, sx);
			p = s8(w, bo2 + 2).mul(xa).add(s8(w, bo2 + 10).mul(xb));
			q = s8(w, bo2 + 18).mul(xc).add(s8(w, bo2 + 26).mul(xd));
			acc2 = step(acc2, lo(p).add(lo(p.rearrange(SWAP_HALVES))).add(lo(q)).add(lo(q.rearrange(SWAP_HALVES))),
					w, bo2, sx);
			p = s8(w, bo3 + 2).mul(xa).add(s8(w, bo3 + 10).mul(xb));
			q = s8(w, bo3 + 18).mul(xc).add(s8(w, bo3 + 26).mul(xd));
			acc3 = step(acc3, lo(p).add(lo(p.rearrange(SWAP_HALVES))).add(lo(q)).add(lo(q.rearrange(SWAP_HALVES))),
					w, bo3, sx);
		}
		r[row] = finish(acc0);
		r[row + 1] = finish(acc1);
		r[row + 2] = finish(acc2);
		r[row + 3] = finish(acc3);
	}

	/** The shipped quantizer (into shorts), narrowed once more for the byte-activation shapes. */
	static void quantize(float[] x, int cols, byte[] xq, short[] xh, double[] xs) {
		VecSimdKernels.quantizeActivationF(x, 0, cols, xh, xs);
		for (int i = 0; i < cols; i++) {
			xq[i] = (byte) xh[i];
		}
	}

	static void widenInterleaved(byte[] xq, short[] xh) {
		int[] perm = { 0, 4, 1, 5, 2, 6, 3, 7 };
		for (int g = 0; g < xq.length; g += 8) {
			for (int k = 0; k < 8; k++) {
				xh[g + k] = xq[g + perm[k]];
			}
		}
	}

	interface Row {

		float row(byte[] w, int base, int nb, short[] xh, double[] xs);

	}

	public static void main(String[] args) {
		int rows = args.length > 0 ? Integer.parseInt(args[0]) : 4096;
		int cols = args.length > 1 ? Integer.parseInt(args[1]) : 4096;
		int rounds = args.length > 2 ? Integer.parseInt(args[2]) : 9;
		String only = args.length > 3 ? args[3] : "";
		int elements = rows * cols;
		int nb = cols / 32;
		Random random = new Random(11);
		float[] wf = new float[elements];
		for (int i = 0; i < elements; i++) {
			wf[i] = (float) (random.nextGaussian() * 0.02);
		}
		byte[] q8 = new byte[elements / 32 * 34 + 8]; // 8 bytes of slack for G / E
		for (int b = 0; b < elements / 32; b++) {
			QuantizedMatrices.quantizeRowQ8_0(wf, b * 32, q8, b * 34);
		}
		float[] x = new float[cols];
		for (int i = 0; i < cols; i++) {
			x[i] = (float) random.nextGaussian();
		}
		float[] r = new float[rows];
		byte[] xq = new byte[cols];
		short[] xh = new short[cols + 8];
		short[] xhi = new short[cols + 8];
		double[] xs = new double[nb];
		int rowBytes = nb * 34;
		int iterations = elements > (1 << 22) ? 10 : 40;
		List<String> names = new ArrayList<>();
		List<Variant> variants = new ArrayList<>();
		names.add("f32 lanes (shipped)");
		variants.add(() -> VecSimdKernels.matvecIntoF(r, wf, rows, cols, x, false));
		names.add("q8 shipped");
		variants.add(() -> VecSimdKernels.matvecIntoQ8F(r, q8, rows, cols, x, false));
		names.add("q8 B (b64 loads, byte x)");
		variants.add(() -> {
			quantize(x, cols, xq, xh, xs);
			for (int row = 0; row < rows; row++) {
				r[row] = rowB(q8, row * rowBytes, nb, xq, xs);
			}
		});
		Object[][] shortRows = { { "q8 Bx (b64, short x)", (Row) Q8Probe::rowBx },
				{ "q8 Bx int-only (timing)", (Row) Q8Probe::rowBxInt },
				{ "q8 scale-only (timing)", (Row) Q8Probe::rowScaleOnly }, { "q8 Bs (rearrange)", (Row) Q8Probe::rowBs },
				{ "q8 G (+4 loads, padded)", (Row) Q8Probe::rowG }, { "q8 H (G/B hybrid)", (Row) Q8Probe::rowH },
				{ "q8 Hs (H + rearrange)", (Row) Q8Probe::rowHs }, { "q8 E (s64, padded)", (Row) Q8Probe::rowE },
				{ "q8 R (other fold)", (Row) Q8Probe::rowR }, { "q8 Bs-vh (varhandle scale)", (Row) Q8Probe::rowBsVh },
				{ "q8 Bs-u2 (two blocks/iter)", (Row) Q8Probe::rowBsU2 } };
		for (Object[] sr : shortRows) {
			names.add((String) sr[0]);
			Row rk = (Row) sr[1];
			variants.add(() -> {
				quantize(x, cols, xq, xh, xs);
				for (int row = 0; row < rows; row++) {
					r[row] = rk.row(q8, row * rowBytes, nb, xh, xs);
				}
			});
		}
		names.add("q8 Q (interleaved, same fold)");
		variants.add(() -> {
			quantize(x, cols, xq, xh, xs);
			widenInterleaved(xq, xhi);
			for (int row = 0; row < rows; row++) {
				r[row] = rowQ(q8, row * rowBytes, nb, xhi, xs);
			}
		});
		names.add("q8 Bx2 (two rows a pass)");
		variants.add(() -> {
			quantize(x, cols, xq, xh, xs);
			for (int row = 0; row < rows; row += 2) {
				rows2Bx(r, row, q8, row * rowBytes, rowBytes, nb, xh, xs);
			}
		});
		names.add("q8 Bs2 (two rows, rearrange)");
		variants.add(() -> {
			quantize(x, cols, xq, xh, xs);
			for (int row = 0; row < rows; row += 2) {
				rows2Bs(r, row, q8, row * rowBytes, rowBytes, nb, xh, xs);
			}
		});
		names.add("q8 Bs4 (four rows, rearrange)");
		variants.add(() -> {
			quantize(x, cols, xq, xh, xs);
			for (int row = 0; row < rows; row += 4) {
				rows4Bs(r, row, q8, row * rowBytes, rowBytes, nb, xh, xs);
			}
		});
		System.out.printf("jit=%s %dx%d rounds=%d x %d calls%n", Q8GemvBenchJit.jit(), rows, cols, rounds, iterations);
		float[] expected = new float[rows];
		VecSimdKernels.matvecIntoQ8F(expected, q8, rows, cols, x, false);
		long baseline = 0;
		for (int i = 0; i < names.size(); i++) {
			if (i > 0 && !only.isEmpty() && !names.get(i).contains(only)) {
				continue;
			}
			long[] samples = time(variants.get(i), iterations, rounds);
			if (i == 0) {
				baseline = samples[0];
			}
			boolean identical = true;
			if (i >= 1) {
				for (int k = 0; k < rows; k++) {
					identical &= Float.floatToRawIntBits(r[k]) == Float.floatToRawIntBits(expected[k]);
				}
			}
			StringBuilder all = new StringBuilder();
			for (long s : samples) {
				all.append(String.format(" %.3f", s / 1e6));
			}
			System.out.printf("%-32s min %8.3f ms  %7.2f Gelem/s  %5.2fx f32  med %8.3f  same-bits=%b  [%s ]%n",
					names.get(i), samples[0] / 1e6, elements / (double) samples[0], baseline / (double) samples[0],
					samples[samples.length / 2] / 1e6, identical, all);
		}
	}

}
