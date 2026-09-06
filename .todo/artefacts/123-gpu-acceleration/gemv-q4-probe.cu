// The Q4 CEILING, measured rather than scaled (.todo/726): what a Q4_0 and a Q8_0 GEMV
// would take on this device at the shapes a Qwen3.5-0.8B forward pass actually launches,
// against the shipped gemv_bf16 (gemm.cu) over the same rows, cold from DRAM. The
// refusal in .kb/gpu.md ("No Q4_0 / Q4_K weight width") was arithmetic on bytes -- a
// Q4_0 matrix is 0.28 of a bf16 one -- and a byte ratio is a ceiling only where the
// kernel is at the bandwidth; these kernels put a number under it.
//
// Both quantized layouts are ggml's, block for block, as a GGUF tensor would land in a
// buffer read straight into place (.kb/quantized-matrix.md): Q8_0 = 34 bytes a block of
// 32 (a binary16 scale then 32 int8), Q4_0 = 18 bytes (the scale then 16 bytes of
// nibbles, element j in the low nibble of byte j and element j + 16 in its high one).
// Neither block is 4-byte aligned, so every load is a 16-bit word -- which is what
// llama.cpp's own kernels do over these blocks too. `L` lanes share a block (1, 2, 4 for
// Q4_0; 1, 2, 4, 8 for Q8_0): the warp covers 32 / L blocks an iteration, each lane
// 16 / L bytes of nibbles (Q4_0) or 32 / L quants (Q8_0), and the block's scale is read
// by every lane that shares it (an L1 hit after the first).
//
// `_split` is the layout question's UPPER BOUND: the same numbers re-packed at upload
// time as a row of scales followed by the quants as 16-byte lines, so a lane reads its
// whole block in ONE 128-bit load (Q4_0) or two (Q8_0). No file has this layout; it says
// what the ggml layout costs against the best any layout could do.
//
// The accumulator is a plain float per block-sum, scaled and added in float. The shipped
// bf16 / f32 kernels carry a compensated pair (gemv_ff); `gemv_q4_0_ff` is the Q4_0 L=2
// kernel with the same pair, to see whether the extra ALU shows at a quarter of the
// bytes an element. The activation is f32 in these first kernels; the `_dp` kernels at the
// end take it Q8-quantized per block, the CPU contract's shape -- and that turned out to be
// the load pattern that matters, not the weight side's (see the comment there).
//
//   nvcc -arch=compute_75 -ptx -fmad=false gemv-q4-probe.cu -o gemv-q4-probe.ptx

#include <cuda_fp16.h>

__device__ __forceinline__ float f16f(unsigned short p) { return __half2float(__ushort_as_half(p)); }

template <int L>
__device__ __forceinline__ void gemv_q4_0(const unsigned short* W, const float* x, float* y, int rows, int cols) {
  int row = (blockIdx.x * blockDim.x + threadIdx.x) >> 5;
  int lane = threadIdx.x & 31;
  if (row >= rows) return;
  int nb = cols >> 5;
  const unsigned short* w = W + (long long) row * nb * 9;
  const int BPI = 32 / L;  // blocks a warp covers per iteration
  const int WPL = 8 / L;   // 16-bit words of nibbles per lane
  int h = lane % L, b0 = lane / L;
  float acc = 0.0f;
  for (int b = b0; b < nb; b += BPI) {
    const unsigned short* blk = w + b * 9;
    float d = f16f(blk[0]);
    const float* xb = x + (b << 5) + h * (2 * WPL);
    float s = 0.0f;
#pragma unroll
    for (int k = 0; k < WPL; k++) {
      unsigned v = blk[1 + h * WPL + k];
      int e = 2 * k;
      s = fmaf((float) ((int) (v & 0xFu) - 8), xb[e], s);
      s = fmaf((float) ((int) ((v >> 4) & 0xFu) - 8), xb[e + 16], s);
      s = fmaf((float) ((int) ((v >> 8) & 0xFu) - 8), xb[e + 1], s);
      s = fmaf((float) ((int) ((v >> 12) & 0xFu) - 8), xb[e + 17], s);
    }
    acc = fmaf(d, s, acc);
  }
  for (int off = 16; off > 0; off >>= 1) acc += __shfl_down_sync(0xffffffffu, acc, off);
  if (lane == 0) y[row] = acc;
}

template <int L>
__device__ __forceinline__ void gemv_q8_0(const unsigned short* W, const float* x, float* y, int rows, int cols) {
  int row = (blockIdx.x * blockDim.x + threadIdx.x) >> 5;
  int lane = threadIdx.x & 31;
  if (row >= rows) return;
  int nb = cols >> 5;
  const unsigned short* w = W + (long long) row * nb * 17;
  const int BPI = 32 / L;
  const int WPL = 16 / L;  // 16-bit words of quants per lane
  int h = lane % L, b0 = lane / L;
  float acc = 0.0f;
  for (int b = b0; b < nb; b += BPI) {
    const unsigned short* blk = w + b * 17;
    float d = f16f(blk[0]);
    const float* xb = x + (b << 5) + h * (2 * WPL);
    float s = 0.0f;
#pragma unroll
    for (int k = 0; k < WPL; k++) {
      unsigned v = blk[1 + h * WPL + k];
      s = fmaf((float) (int) (signed char) (v & 0xFFu), xb[2 * k], s);
      s = fmaf((float) (int) (signed char) (v >> 8), xb[2 * k + 1], s);
    }
    acc = fmaf(d, s, acc);
  }
  for (int off = 16; off > 0; off >>= 1) acc += __shfl_down_sync(0xffffffffu, acc, off);
  if (lane == 0) y[row] = acc;
}

extern "C" __global__ void gemv_q4_0_l1(const unsigned short* W, const float* x, float* y, int rows, int cols) { gemv_q4_0<1>(W, x, y, rows, cols); }
extern "C" __global__ void gemv_q4_0_l2(const unsigned short* W, const float* x, float* y, int rows, int cols) { gemv_q4_0<2>(W, x, y, rows, cols); }
extern "C" __global__ void gemv_q4_0_l4(const unsigned short* W, const float* x, float* y, int rows, int cols) { gemv_q4_0<4>(W, x, y, rows, cols); }
extern "C" __global__ void gemv_q8_0_l1(const unsigned short* W, const float* x, float* y, int rows, int cols) { gemv_q8_0<1>(W, x, y, rows, cols); }
extern "C" __global__ void gemv_q8_0_l2(const unsigned short* W, const float* x, float* y, int rows, int cols) { gemv_q8_0<2>(W, x, y, rows, cols); }
extern "C" __global__ void gemv_q8_0_l4(const unsigned short* W, const float* x, float* y, int rows, int cols) { gemv_q8_0<4>(W, x, y, rows, cols); }
extern "C" __global__ void gemv_q8_0_l8(const unsigned short* W, const float* x, float* y, int rows, int cols) { gemv_q8_0<8>(W, x, y, rows, cols); }

// The split layout: per row, nb binary16 scales (padded to a 16-byte boundary), then the
// quants of block b as 16-byte line(s) b. A lane owns a block.
__device__ __forceinline__ int split_scale_words(int nb) { return (nb + 7) & ~7; }

extern "C" __global__ void gemv_q4_0_split(const unsigned short* W, const float* x, float* y, int rows, int cols) {
  int row = (blockIdx.x * blockDim.x + threadIdx.x) >> 5;
  int lane = threadIdx.x & 31;
  if (row >= rows) return;
  int nb = cols >> 5;
  int sw = split_scale_words(nb);
  const unsigned short* scales = W + (long long) row * (sw + nb * 8);
  const uint4* q = (const uint4*) (scales + sw);
  float acc = 0.0f;
  for (int b = lane; b < nb; b += 32) {
    float d = f16f(scales[b]);
    uint4 v = q[b];
    const float* xb = x + (b << 5);
    float s = 0.0f;
    unsigned words[4] = { v.x, v.y, v.z, v.w };
#pragma unroll
    for (int k = 0; k < 4; k++) {
      unsigned u = words[k];
#pragma unroll
      for (int j = 0; j < 4; j++) {
        unsigned byte = (u >> (8 * j)) & 0xFFu;
        int e = 4 * k + j;
        s = fmaf((float) ((int) (byte & 0xFu) - 8), xb[e], s);
        s = fmaf((float) ((int) (byte >> 4) - 8), xb[e + 16], s);
      }
    }
    acc = fmaf(d, s, acc);
  }
  for (int off = 16; off > 0; off >>= 1) acc += __shfl_down_sync(0xffffffffu, acc, off);
  if (lane == 0) y[row] = acc;
}

extern "C" __global__ void gemv_q8_0_split(const unsigned short* W, const float* x, float* y, int rows, int cols) {
  int row = (blockIdx.x * blockDim.x + threadIdx.x) >> 5;
  int lane = threadIdx.x & 31;
  if (row >= rows) return;
  int nb = cols >> 5;
  int sw = split_scale_words(nb);
  const unsigned short* scales = W + (long long) row * (sw + nb * 16);
  const uint4* q = (const uint4*) (scales + sw);
  float acc = 0.0f;
  for (int b = lane; b < nb; b += 32) {
    float d = f16f(scales[b]);
    uint4 v0 = q[2 * b], v1 = q[2 * b + 1];
    const float* xb = x + (b << 5);
    float s = 0.0f;
    unsigned words[8] = { v0.x, v0.y, v0.z, v0.w, v1.x, v1.y, v1.z, v1.w };
#pragma unroll
    for (int k = 0; k < 8; k++) {
      unsigned u = words[k];
#pragma unroll
      for (int j = 0; j < 4; j++) {
        s = fmaf((float) (int) (signed char) ((u >> (8 * j)) & 0xFFu), xb[4 * k + j], s);
      }
    }
    acc = fmaf(d, s, acc);
  }
  for (int off = 16; off > 0; off >>= 1) acc += __shfl_down_sync(0xffffffffu, acc, off);
  if (lane == 0) y[row] = acc;
}

// The Q4_0 L=2 kernel with the shipped kernels' compensated accumulator across blocks
// (the block-sum stays a float: 32 products of a 4-bit integer and an f32).
extern "C" __global__ void gemv_q4_0_ff(const unsigned short* W, const float* x, float* y, int rows, int cols) {
  int row = (blockIdx.x * blockDim.x + threadIdx.x) >> 5;
  int lane = threadIdx.x & 31;
  if (row >= rows) return;
  int nb = cols >> 5;
  const unsigned short* w = W + (long long) row * nb * 9;
  int h = lane & 1, b0 = lane >> 1;
  float hi = 0.0f, lo = 0.0f;
  for (int b = b0; b < nb; b += 16) {
    const unsigned short* blk = w + b * 9;
    float d = f16f(blk[0]);
    const float* xb = x + (b << 5) + h * 8;
    float s = 0.0f;
#pragma unroll
    for (int k = 0; k < 4; k++) {
      unsigned v = blk[1 + h * 4 + k];
      int e = 2 * k;
      s = fmaf((float) ((int) (v & 0xFu) - 8), xb[e], s);
      s = fmaf((float) ((int) ((v >> 4) & 0xFu) - 8), xb[e + 16], s);
      s = fmaf((float) ((int) ((v >> 8) & 0xFu) - 8), xb[e + 1], s);
      s = fmaf((float) ((int) ((v >> 12) & 0xFu) - 8), xb[e + 17], s);
    }
    float p = d * s;
    float pe = fmaf(d, s, -p);
    float t = hi + p;
    float bv = t - hi;
    float err = (hi - (t - bv)) + (p - bv);
    hi = t;
    lo += err + pe;
  }
  for (int off = 16; off > 0; off >>= 1) {
    float ohi = __shfl_down_sync(0xffffffffu, hi, off);
    float olo = __shfl_down_sync(0xffffffffu, lo, off);
    float t = hi + ohi;
    float bv = t - hi;
    float err = (hi - (t - bv)) + (ohi - bv);
    hi = t;
    lo += olo + err;
  }
  if (lane == 0) y[row] = hi + lo;
}

// The INTEGER-DOT shape, which is what the CPU's Q8_0 kernel already computes
// (.kb/quantized-matrix.md: the activation quantized per block of 32 to int8 with its own
// scale, one integer dot a block, then d * sx * idot) and what a Q4 width would inherit:
// `X` is [nb float scales, padded to 16 bytes][cols int8]. It changes the x-side traffic
// as much as the weight side -- 32 bytes a block instead of 128, read as 32-bit words --
// which the f32-x kernels above pay for at stride: a lane owning a block reads x at a
// 128-byte stride from its neighbours, 32 L1 transactions a load. With L lanes a block
// the lanes of one block read adjacent words. The dot is __dp4a (sm_61+); a nibble
// byte is 0..15, so it is a signed operand as it stands and the -8 is one dp4a against
// 0x01010101 per word.
template <int L>
__device__ __forceinline__ void gemv_q4_0_dp(const unsigned short* W, const unsigned char* X, float* y, int rows, int cols) {
  int row = (blockIdx.x * blockDim.x + threadIdx.x) >> 5;
  int lane = threadIdx.x & 31;
  if (row >= rows) return;
  int nb = cols >> 5;
  int spad = (nb * 4 + 15) & ~15;
  const float* xs = (const float*) X;
  const int* xq = (const int*) (X + spad);
  const unsigned short* w = W + (long long) row * nb * 9;
  const int BPI = 32 / L;
  const int WPL = 8 / L;  // 16-bit words of nibbles per lane = 2 * WPL bytes = WPL / 2 int words of x
  int h = lane % L, b0 = lane / L;
  float acc = 0.0f;
  for (int b = b0; b < nb; b += BPI) {
    const unsigned short* blk = w + b * 9;
    float d = f16f(blk[0]) * xs[b];
    const int* xb = xq + (b << 3) + h * (WPL / 2);  // this lane's first int of the LOW half
    int isum = 0;
#pragma unroll
    for (int k = 0; k < WPL / 2; k++) {
      unsigned v0 = blk[1 + h * WPL + 2 * k], v1 = blk[1 + h * WPL + 2 * k + 1];
      unsigned packed = v0 | (v1 << 16);  // 4 nibble bytes: elements 4k..4k+3 low, +16 high
      int lo = (int) (packed & 0x0F0F0F0Fu);
      int hi = (int) ((packed >> 4) & 0x0F0F0F0Fu);
      int xl = xb[k], xh = xb[k + 4];
      isum = __dp4a(lo, xl, isum);
      isum = __dp4a(hi, xh, isum);
      isum -= 8 * (__dp4a(0x01010101, xl, 0) + __dp4a(0x01010101, xh, 0));
    }
    acc = fmaf(d, (float) isum, acc);
  }
  for (int off = 16; off > 0; off >>= 1) acc += __shfl_down_sync(0xffffffffu, acc, off);
  if (lane == 0) y[row] = acc;
}

template <int L>
__device__ __forceinline__ void gemv_q8_0_dp(const unsigned short* W, const unsigned char* X, float* y, int rows, int cols) {
  int row = (blockIdx.x * blockDim.x + threadIdx.x) >> 5;
  int lane = threadIdx.x & 31;
  if (row >= rows) return;
  int nb = cols >> 5;
  int spad = (nb * 4 + 15) & ~15;
  const float* xs = (const float*) X;
  const int* xq = (const int*) (X + spad);
  const unsigned short* w = W + (long long) row * nb * 17;
  const int BPI = 32 / L;
  const int WPL = 16 / L;  // 16-bit words of quants per lane = WPL / 2 int words of x
  int h = lane % L, b0 = lane / L;
  float acc = 0.0f;
  for (int b = b0; b < nb; b += BPI) {
    const unsigned short* blk = w + b * 17;
    float d = f16f(blk[0]) * xs[b];
    const int* xb = xq + (b << 3) + h * (WPL / 2);
    int isum = 0;
#pragma unroll
    for (int k = 0; k < WPL / 2; k++) {
      unsigned v0 = blk[1 + h * WPL + 2 * k], v1 = blk[1 + h * WPL + 2 * k + 1];
      isum = __dp4a((int) (v0 | (v1 << 16)), xb[k], isum);
    }
    acc = fmaf(d, (float) isum, acc);
  }
  for (int off = 16; off > 0; off >>= 1) acc += __shfl_down_sync(0xffffffffu, acc, off);
  if (lane == 0) y[row] = acc;
}

extern "C" __global__ void gemv_q4_0_dp2(const unsigned short* W, const unsigned char* X, float* y, int rows, int cols) { gemv_q4_0_dp<2>(W, X, y, rows, cols); }
extern "C" __global__ void gemv_q4_0_dp4(const unsigned short* W, const unsigned char* X, float* y, int rows, int cols) { gemv_q4_0_dp<4>(W, X, y, rows, cols); }
extern "C" __global__ void gemv_q8_0_dp4(const unsigned short* W, const unsigned char* X, float* y, int rows, int cols) { gemv_q8_0_dp<4>(W, X, y, rows, cols); }
extern "C" __global__ void gemv_q8_0_dp8(const unsigned short* W, const unsigned char* X, float* y, int rows, int cols) { gemv_q8_0_dp<8>(W, X, y, rows, cols); }
