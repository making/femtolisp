// The Q8_0 GEMV that is the CPU kernel's BITS (.todo/728): candidates for gemm.cu's
// gemv_q8_0, each computing vec::%matvec-quantized exactly as .kb/quantized-matrix.md
// pins it -- the activation quantized per block of 32 (amax / 127 in double, CL round =
// rint), FOUR exact integer lane sums a block (lane i over the columns j with j mod 4 = i),
// per lane ONE f32 multiply-add of the lane sum against p = f32(f64(d) * sx), the four
// f32 accumulators walked over the blocks IN ORDER, and the row folded
// (acc0 + acc2) + (acc1 + acc3). An integer sum is order-free, so the columns of a lane
// may be split across threads and re-joined by shuffles; the f32 chain is not, so a row
// has exactly four sequential chains, one a thread, and the question this probe answers
// is whether 4 x rows (or 8 x rows) threads still stream the matrix at the bandwidth
// gemv-q4-probe.cu's gemv_q8_0_dp4 reached (0.50-0.58 of gemv_bf16 a forward).
//
//   _x4: four threads a row; thread t loads columns 8t..8t+7 of a block as two 16-bit
//        words each (a block is 34 bytes, so only 2-byte alignment is guaranteed), forms
//        its four lane partials, and a 4x4 transpose-reduce over two xor shuffles leaves
//        lane t's block sum on thread t.
//   _x8: eight threads a row; thread t loads columns 4t..4t+3 (one word), one product a
//        lane, three xor rounds to reduce; threads t and t + 4 then walk the same chain.
//   _x4r: _x4 with the block's scale product computed by every thread (one double
//        multiply per block per thread) instead of once per quad and shuffled -- to see
//        whether the fp64 rate shows.
//   _b8 / _b4 / _b8d (appended below): ONE LANE A THREAD, no transpose -- the activation's
//        quants permuted at quantization time so a thread's columns are one aligned word;
//        _b8 is what gemm.cu ships as gemv_q8_0 (5.3 ms a forward against bf16's 7.7).
//
// X is [nb doubles: the activation's per-block scale sx][cols int8: the quants], written
// by quantize_q8_0 below (one warp per block). gemm.cu is compiled -fmad=false; the pinned
// f32 steps are spelled with the _rn intrinsics as well, so no build flag can fuse them.
//
//   nvcc -arch=compute_75 -ptx -fmad=false gemv-q8-exact-probe.cu -o gemv-q8-exact-probe.ptx

#include <cuda_fp16.h>

__device__ __forceinline__ float f16f(unsigned short p) { return __half2float(__ushort_as_half(p)); }

/** Byte k of a word, sign-extended. */
__device__ __forceinline__ int sb(unsigned w, int k) { return ((int) (w << (24 - 8 * k))) >> 24; }

/** f32(f64(d) * sx): the defun's scale product, double then narrowed once. */
__device__ __forceinline__ float scale_product(unsigned short d, double sx) {
  return __double2float_rn(__dmul_rn((double) f16f(d), sx));
}

/**
 * The four lane partials of this thread's eight columns (8t .. 8t + 7 of the block): the
 * two words hold columns 8t + k with lane k & 3.
 */
__device__ __forceinline__ void partials8(unsigned w0, unsigned w1, unsigned x0, unsigned x1, int& s0, int& s1,
                                          int& s2, int& s3) {
  s0 = sb(w0, 0) * sb(x0, 0) + sb(w1, 0) * sb(x1, 0);
  s1 = sb(w0, 1) * sb(x0, 1) + sb(w1, 1) * sb(x1, 1);
  s2 = sb(w0, 2) * sb(x0, 2) + sb(w1, 2) * sb(x1, 2);
  s3 = sb(w0, 3) * sb(x0, 3) + sb(w1, 3) * sb(x1, 3);
}

/**
 * The 4x4 transpose-reduce across the four threads of a quad: thread t answers the sum
 * over the quad of lane t's partial.
 */
__device__ __forceinline__ int quad_lane_sum(int t, int s0, int s1, int s2, int s3) {
  int hi = t & 2;
  int keepA = hi ? s2 : s0, keepB = hi ? s3 : s1;
  int sendA = hi ? s0 : s2, sendB = hi ? s1 : s3;
  keepA += __shfl_xor_sync(0xffffffffu, sendA, 2);
  keepB += __shfl_xor_sync(0xffffffffu, sendB, 2);
  int lo = t & 1;
  int keep = lo ? keepB : keepA, send = lo ? keepA : keepB;
  return keep + __shfl_xor_sync(0xffffffffu, send, 1);
}

/** (acc0 + acc2) + (acc1 + acc3) over a quad, answered on thread 0 of it. */
__device__ __forceinline__ float quad_fold(float acc) {
  float s = __fadd_rn(acc, __shfl_xor_sync(0xffffffffu, acc, 2));
  return __fadd_rn(s, __shfl_xor_sync(0xffffffffu, s, 1));
}

extern "C" __global__ void gemv_q8_0_x4(const unsigned char* W, const unsigned char* X, float* y, int rows, int cols) {
  int t = threadIdx.x & 3;
  int row = (blockIdx.x * blockDim.x + threadIdx.x) >> 2;
  // No early return: the quad's shuffles need every thread, so a tail thread computes
  // the last row again and does not store it.
  bool live = row < rows;
  if (!live) row = rows - 1;
  int nb = cols >> 5;
  const double* xs = (const double*) X;
  const unsigned char* xq = X + (size_t) nb * 8;
  const unsigned char* w = W + (size_t) row * nb * 34;
  float acc = 0.0f;
  int b = 0;
  for (; b + 4 <= nb; b += 4) {
    // The scale product of block b + t, by thread t, shared round the quad below.
    float pm = scale_product(*(const unsigned short*) (w + (b + t) * 34), xs[b + t]);
#pragma unroll
    for (int k = 0; k < 4; k++) {
      const unsigned char* blk = w + (b + k) * 34;
      const unsigned short* q = (const unsigned short*) (blk + 2 + 8 * t);
      unsigned w0 = q[0] | ((unsigned) q[1] << 16), w1 = q[2] | ((unsigned) q[3] << 16);
      const unsigned* xw = (const unsigned*) (xq + ((b + k) << 5) + 8 * t);
      unsigned x0 = xw[0], x1 = xw[1];
      int s0, s1, s2, s3;
      partials8(w0, w1, x0, x1, s0, s1, s2, s3);
      int isum = quad_lane_sum(t, s0, s1, s2, s3);
      float p = __shfl_sync(0xffffffffu, pm, ((threadIdx.x & 31) & ~3) + k);
      acc = __fadd_rn(acc, __fmul_rn(__int2float_rn(isum), p));
    }
  }
  for (; b < nb; b++) {
    const unsigned char* blk = w + b * 34;
    const unsigned short* q = (const unsigned short*) (blk + 2 + 8 * t);
    unsigned w0 = q[0] | ((unsigned) q[1] << 16), w1 = q[2] | ((unsigned) q[3] << 16);
    const unsigned* xw = (const unsigned*) (xq + (b << 5) + 8 * t);
    int s0, s1, s2, s3;
    partials8(w0, w1, xw[0], xw[1], s0, s1, s2, s3);
    int isum = quad_lane_sum(t, s0, s1, s2, s3);
    float p = scale_product(*(const unsigned short*) blk, xs[b]);
    acc = __fadd_rn(acc, __fmul_rn(__int2float_rn(isum), p));
  }
  float r = quad_fold(acc);
  if (t == 0 && live) y[row] = r;
}

extern "C" __global__ void gemv_q8_0_x4r(const unsigned char* W, const unsigned char* X, float* y, int rows,
                                         int cols) {
  int t = threadIdx.x & 3;
  int row = (blockIdx.x * blockDim.x + threadIdx.x) >> 2;
  bool live = row < rows;
  if (!live) row = rows - 1;
  int nb = cols >> 5;
  const double* xs = (const double*) X;
  const unsigned char* xq = X + (size_t) nb * 8;
  const unsigned char* w = W + (size_t) row * nb * 34;
  float acc = 0.0f;
#pragma unroll 4
  for (int b = 0; b < nb; b++) {
    const unsigned char* blk = w + b * 34;
    const unsigned short* q = (const unsigned short*) (blk + 2 + 8 * t);
    unsigned w0 = q[0] | ((unsigned) q[1] << 16), w1 = q[2] | ((unsigned) q[3] << 16);
    const unsigned* xw = (const unsigned*) (xq + (b << 5) + 8 * t);
    int s0, s1, s2, s3;
    partials8(w0, w1, xw[0], xw[1], s0, s1, s2, s3);
    int isum = quad_lane_sum(t, s0, s1, s2, s3);
    float p = scale_product(*(const unsigned short*) blk, xs[b]);
    acc = __fadd_rn(acc, __fmul_rn(__int2float_rn(isum), p));
  }
  float r = quad_fold(acc);
  if (t == 0 && live) y[row] = r;
}

extern "C" __global__ void gemv_q8_0_x8(const unsigned char* W, const unsigned char* X, float* y, int rows, int cols) {
  int t = threadIdx.x & 7;
  int row = (blockIdx.x * blockDim.x + threadIdx.x) >> 3;
  bool live = row < rows;
  if (!live) row = rows - 1;
  int nb = cols >> 5;
  const double* xs = (const double*) X;
  const unsigned char* xq = X + (size_t) nb * 8;
  const unsigned char* w = W + (size_t) row * nb * 34;
  float acc = 0.0f;
  int b = 0;
  for (; b + 8 <= nb; b += 8) {
    float pm = scale_product(*(const unsigned short*) (w + (b + t) * 34), xs[b + t]);
#pragma unroll
    for (int k = 0; k < 8; k++) {
      const unsigned char* blk = w + (b + k) * 34;
      const unsigned short* q = (const unsigned short*) (blk + 2 + 4 * t);
      unsigned wv = q[0] | ((unsigned) q[1] << 16);
      unsigned xv = *(const unsigned*) (xq + ((b + k) << 5) + 4 * t);
      int s0 = sb(wv, 0) * sb(xv, 0), s1 = sb(wv, 1) * sb(xv, 1), s2 = sb(wv, 2) * sb(xv, 2),
          s3 = sb(wv, 3) * sb(xv, 3);
      s0 += __shfl_xor_sync(0xffffffffu, s0, 4);
      s1 += __shfl_xor_sync(0xffffffffu, s1, 4);
      s2 += __shfl_xor_sync(0xffffffffu, s2, 4);
      s3 += __shfl_xor_sync(0xffffffffu, s3, 4);
      int isum = quad_lane_sum(t & 3, s0, s1, s2, s3);
      float p = __shfl_sync(0xffffffffu, pm, ((threadIdx.x & 31) & ~7) + k);
      acc = __fadd_rn(acc, __fmul_rn(__int2float_rn(isum), p));
    }
  }
  for (; b < nb; b++) {
    const unsigned char* blk = w + b * 34;
    const unsigned short* q = (const unsigned short*) (blk + 2 + 4 * t);
    unsigned wv = q[0] | ((unsigned) q[1] << 16);
    unsigned xv = *(const unsigned*) (xq + (b << 5) + 4 * t);
    int s0 = sb(wv, 0) * sb(xv, 0), s1 = sb(wv, 1) * sb(xv, 1), s2 = sb(wv, 2) * sb(xv, 2),
        s3 = sb(wv, 3) * sb(xv, 3);
    s0 += __shfl_xor_sync(0xffffffffu, s0, 4);
    s1 += __shfl_xor_sync(0xffffffffu, s1, 4);
    s2 += __shfl_xor_sync(0xffffffffu, s2, 4);
    s3 += __shfl_xor_sync(0xffffffffu, s3, 4);
    int isum = quad_lane_sum(t & 3, s0, s1, s2, s3);
    float p = scale_product(*(const unsigned short*) blk, xs[b]);
    acc = __fadd_rn(acc, __fmul_rn(__int2float_rn(isum), p));
  }
  float r = quad_fold(acc);
  if (t == 0 && live) y[row] = r;
}

// The activation quantizer, the CPU contract's: per block of 32, amax over |x| with a
// strict > (a NaN never raises it), sx = amax / 127 in double, q = rint(x / sx) in double
// (CL round: half to even), everything 0 where sx is. One warp per block.
extern "C" __global__ void quantize_q8_0(const float* x, unsigned char* X, int cols) {
  int nb = cols >> 5;
  int b = (blockIdx.x * blockDim.x + threadIdx.x) >> 5;
  int lane = threadIdx.x & 31;
  if (b >= nb) return;
  float v = x[(b << 5) + lane];
  float a = fabsf(v);
  float m = a > 0.0f ? a : 0.0f;
  for (int off = 16; off > 0; off >>= 1) m = fmaxf(m, __shfl_xor_sync(0xffffffffu, m, off));
  double sx = (double) m / 127.0;
  if (lane == 0) ((double*) X)[b] = sx;
  int q = sx == 0.0 ? 0 : __double2int_rn((double) v / sx);
  X[(size_t) nb * 8 + (b << 5) + lane] = (unsigned char) q;
}

// ONE LANE A THREAD: no transpose. The activation's quants are PERMUTED at quantization
// time so that a thread's columns are one aligned word (two for _b4), and the weight's
// are read as sign-extending byte loads at stride 4 -- the block's four lanes are
// interleaved, so a thread's eight quants are bytes i, i + 4, ..., i + 28.
//
//   _b8: eight threads a row; thread t = 4h + i owns lane i's columns 4k + i for k in
//        [4h, 4h + 4), one xor-4 shuffle joins the two halves. X's block b holds, at word
//        t, thread t's four quants.
//   _b4: four threads a row; thread i owns all eight columns of lane i, no integer
//        shuffle at all. X's block b holds, at words 2i and 2i + 1, thread i's eight quants.
extern "C" __global__ void gemv_q8_0_b8(const unsigned char* W, const unsigned char* X, float* y, int rows, int cols) {
  int t = threadIdx.x & 7, i = t & 3, h = t >> 2;
  int row = (blockIdx.x * blockDim.x + threadIdx.x) >> 3;
  bool live = row < rows;
  if (!live) row = rows - 1;
  int nb = cols >> 5;
  const double* xs = (const double*) X;
  const unsigned char* xq = X + (size_t) nb * 8;
  const unsigned char* w = W + (size_t) row * nb * 34;
  float acc = 0.0f;
  int b = 0;
  for (; b + 8 <= nb; b += 8) {
    float pm = scale_product(*(const unsigned short*) (w + (b + t) * 34), xs[b + t]);
#pragma unroll
    for (int k = 0; k < 8; k++) {
      const signed char* q = (const signed char*) (w + (b + k) * 34 + 2 + 16 * h + i);
      unsigned xv = *(const unsigned*) (xq + ((b + k) << 5) + 4 * t);
      int s = q[0] * sb(xv, 0) + q[4] * sb(xv, 1) + q[8] * sb(xv, 2) + q[12] * sb(xv, 3);
      s += __shfl_xor_sync(0xffffffffu, s, 4);
      float p = __shfl_sync(0xffffffffu, pm, ((threadIdx.x & 31) & ~7) + k);
      acc = __fadd_rn(acc, __fmul_rn(__int2float_rn(s), p));
    }
  }
  for (; b < nb; b++) {
    const signed char* q = (const signed char*) (w + b * 34 + 2 + 16 * h + i);
    unsigned xv = *(const unsigned*) (xq + (b << 5) + 4 * t);
    int s = q[0] * sb(xv, 0) + q[4] * sb(xv, 1) + q[8] * sb(xv, 2) + q[12] * sb(xv, 3);
    s += __shfl_xor_sync(0xffffffffu, s, 4);
    float p = scale_product(*(const unsigned short*) (w + b * 34), xs[b]);
    acc = __fadd_rn(acc, __fmul_rn(__int2float_rn(s), p));
  }
  float r = quad_fold(acc);
  if (t == 0 && live) y[row] = r;
}

extern "C" __global__ void gemv_q8_0_b4(const unsigned char* W, const unsigned char* X, float* y, int rows, int cols) {
  int i = threadIdx.x & 3;
  int row = (blockIdx.x * blockDim.x + threadIdx.x) >> 2;
  bool live = row < rows;
  if (!live) row = rows - 1;
  int nb = cols >> 5;
  const double* xs = (const double*) X;
  const unsigned char* xq = X + (size_t) nb * 8;
  const unsigned char* w = W + (size_t) row * nb * 34;
  float acc = 0.0f;
  int b = 0;
  for (; b + 4 <= nb; b += 4) {
    float pm = scale_product(*(const unsigned short*) (w + (b + i) * 34), xs[b + i]);
#pragma unroll
    for (int k = 0; k < 4; k++) {
      const signed char* q = (const signed char*) (w + (b + k) * 34 + 2 + i);
      const unsigned* xw = (const unsigned*) (xq + ((b + k) << 5) + 8 * i);
      unsigned x0 = xw[0], x1 = xw[1];
      int s = q[0] * sb(x0, 0) + q[4] * sb(x0, 1) + q[8] * sb(x0, 2) + q[12] * sb(x0, 3) + q[16] * sb(x1, 0)
              + q[20] * sb(x1, 1) + q[24] * sb(x1, 2) + q[28] * sb(x1, 3);
      float p = __shfl_sync(0xffffffffu, pm, ((threadIdx.x & 31) & ~3) + k);
      acc = __fadd_rn(acc, __fmul_rn(__int2float_rn(s), p));
    }
  }
  for (; b < nb; b++) {
    const signed char* q = (const signed char*) (w + b * 34 + 2 + i);
    const unsigned* xw = (const unsigned*) (xq + (b << 5) + 8 * i);
    unsigned x0 = xw[0], x1 = xw[1];
    int s = q[0] * sb(x0, 0) + q[4] * sb(x0, 1) + q[8] * sb(x0, 2) + q[12] * sb(x0, 3) + q[16] * sb(x1, 0)
            + q[20] * sb(x1, 1) + q[24] * sb(x1, 2) + q[28] * sb(x1, 3);
    float p = scale_product(*(const unsigned short*) (w + b * 34), xs[b]);
    acc = __fadd_rn(acc, __fmul_rn(__int2float_rn(s), p));
  }
  float r = quad_fold(acc);
  if (i == 0 && live) y[row] = r;
}

// _b8d: _b8 with the four weight quants of a thread packed into one word (three byte
// permutes over unsigned byte loads) and the lane partial as ONE __dp4a -- the same
// exact integer, fewer instructions.
extern "C" __global__ void gemv_q8_0_b8d(const unsigned char* W, const unsigned char* X, float* y, int rows,
                                         int cols) {
  int t = threadIdx.x & 7, i = t & 3, h = t >> 2;
  int row = (blockIdx.x * blockDim.x + threadIdx.x) >> 3;
  bool live = row < rows;
  if (!live) row = rows - 1;
  int nb = cols >> 5;
  const double* xs = (const double*) X;
  const unsigned char* xq = X + (size_t) nb * 8;
  const unsigned char* w = W + (size_t) row * nb * 34;
  float acc = 0.0f;
  int b = 0;
  for (; b + 8 <= nb; b += 8) {
    float pm = scale_product(*(const unsigned short*) (w + (b + t) * 34), xs[b + t]);
#pragma unroll
    for (int k = 0; k < 8; k++) {
      const unsigned char* q = w + (b + k) * 34 + 2 + 16 * h + i;
      unsigned q0 = q[0], q1 = q[4], q2 = q[8], q3 = q[12];
      unsigned wv = __byte_perm(__byte_perm(q0, q1, 0x1140), __byte_perm(q2, q3, 0x1140), 0x5410);
      int xv = *(const int*) (xq + ((b + k) << 5) + 4 * t);
      int s = __dp4a((int) wv, xv, 0);
      s += __shfl_xor_sync(0xffffffffu, s, 4);
      float p = __shfl_sync(0xffffffffu, pm, ((threadIdx.x & 31) & ~7) + k);
      acc = __fadd_rn(acc, __fmul_rn(__int2float_rn(s), p));
    }
  }
  for (; b < nb; b++) {
    const unsigned char* q = w + b * 34 + 2 + 16 * h + i;
    unsigned q0 = q[0], q1 = q[4], q2 = q[8], q3 = q[12];
    unsigned wv = __byte_perm(__byte_perm(q0, q1, 0x1140), __byte_perm(q2, q3, 0x1140), 0x5410);
    int xv = *(const int*) (xq + (b << 5) + 4 * t);
    int s = __dp4a((int) wv, xv, 0);
    s += __shfl_xor_sync(0xffffffffu, s, 4);
    float p = scale_product(*(const unsigned short*) (w + b * 34), xs[b]);
    acc = __fadd_rn(acc, __fmul_rn(__int2float_rn(s), p));
  }
  float r = quad_fold(acc);
  if (t == 0 && live) y[row] = r;
}
