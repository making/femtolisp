// The bfloat16 GEMV's LOAD WIDTH (.todo/490): the shipped gemv_bf16 reads one 16-bit
// pattern per lane per iteration, so a warp-load is 64 contiguous bytes where the f32
// kernel's is 128 -- and the first shipped-route measurement put the bf16 kernel at half
// the device's bandwidth (137 GB/s over the 508 MB Qwen head against 231 for f32). The
// candidates widen the per-lane load to 2 / 4 / 8 patterns (a 32 / 64 / 128-bit load);
// each keeps ONE double accumulator per lane and folds the patterns of a load in column
// order, so the summation order is "lane l owns columns kl..kl+k-1, kl+32k.." -- the same
// order for every k, which is what a widened f32 sibling (gemv_f32_v4, below, float4 loads)
// would need to share for the bf16 == f32-over-widened equivalence to survive a change.
// cols must be a multiple of the load width; the probe arranges it.
//
//   nvcc -arch=compute_75 -ptx -fmad=false gemv-bf16-probe.cu -o gemv-bf16-probe.ptx

__device__ __forceinline__ float bf16f(unsigned short p) { return __uint_as_float(((unsigned) p) << 16); }

extern "C" __global__ void gemv_bf16_x1(const unsigned short* W, const float* x, float* y, int rows, int cols) {
  int row = (blockIdx.x * blockDim.x + threadIdx.x) >> 5;
  int lane = threadIdx.x & 31;
  if (row >= rows) return;
  const unsigned short* w = W + (long long) row * cols;
  double acc = 0.0;
  for (int j = lane; j < cols; j += 32) acc = fma((double) bf16f(w[j]), (double) x[j], acc);
  for (int off = 16; off > 0; off >>= 1) acc += __shfl_down_sync(0xffffffffu, acc, off);
  if (lane == 0) y[row] = (float) acc;
}

extern "C" __global__ void gemv_bf16_x2(const unsigned short* W, const float* x, float* y, int rows, int cols) {
  int row = (blockIdx.x * blockDim.x + threadIdx.x) >> 5;
  int lane = threadIdx.x & 31;
  if (row >= rows) return;
  const unsigned* w = (const unsigned*) (W + (long long) row * cols);
  const float2* xv = (const float2*) x;
  double acc = 0.0;
  int pairs = cols >> 1;
  for (int j = lane; j < pairs; j += 32) {
    unsigned a = w[j];
    float2 b = xv[j];
    acc = fma((double) __uint_as_float(a << 16), (double) b.x, acc);
    acc = fma((double) __uint_as_float(a & 0xffff0000u), (double) b.y, acc);
  }
  for (int off = 16; off > 0; off >>= 1) acc += __shfl_down_sync(0xffffffffu, acc, off);
  if (lane == 0) y[row] = (float) acc;
}

extern "C" __global__ void gemv_bf16_x4(const unsigned short* W, const float* x, float* y, int rows, int cols) {
  int row = (blockIdx.x * blockDim.x + threadIdx.x) >> 5;
  int lane = threadIdx.x & 31;
  if (row >= rows) return;
  const uint2* w = (const uint2*) (W + (long long) row * cols);
  const float4* xv = (const float4*) x;
  double acc = 0.0;
  int quads = cols >> 2;
  for (int j = lane; j < quads; j += 32) {
    uint2 a = w[j];
    float4 b = xv[j];
    acc = fma((double) __uint_as_float(a.x << 16), (double) b.x, acc);
    acc = fma((double) __uint_as_float(a.x & 0xffff0000u), (double) b.y, acc);
    acc = fma((double) __uint_as_float(a.y << 16), (double) b.z, acc);
    acc = fma((double) __uint_as_float(a.y & 0xffff0000u), (double) b.w, acc);
  }
  for (int off = 16; off > 0; off >>= 1) acc += __shfl_down_sync(0xffffffffu, acc, off);
  if (lane == 0) y[row] = (float) acc;
}

extern "C" __global__ void gemv_bf16_x8(const unsigned short* W, const float* x, float* y, int rows, int cols) {
  int row = (blockIdx.x * blockDim.x + threadIdx.x) >> 5;
  int lane = threadIdx.x & 31;
  if (row >= rows) return;
  const uint4* w = (const uint4*) (W + (long long) row * cols);
  const float4* xv = (const float4*) x;
  double acc = 0.0;
  int octs = cols >> 3;
  for (int j = lane; j < octs; j += 32) {
    uint4 a = w[j];
    float4 b0 = xv[2 * j], b1 = xv[2 * j + 1];
    acc = fma((double) __uint_as_float(a.x << 16), (double) b0.x, acc);
    acc = fma((double) __uint_as_float(a.x & 0xffff0000u), (double) b0.y, acc);
    acc = fma((double) __uint_as_float(a.y << 16), (double) b0.z, acc);
    acc = fma((double) __uint_as_float(a.y & 0xffff0000u), (double) b0.w, acc);
    acc = fma((double) __uint_as_float(a.z << 16), (double) b1.x, acc);
    acc = fma((double) __uint_as_float(a.z & 0xffff0000u), (double) b1.y, acc);
    acc = fma((double) __uint_as_float(a.w << 16), (double) b1.z, acc);
    acc = fma((double) __uint_as_float(a.w & 0xffff0000u), (double) b1.w, acc);
  }
  for (int off = 16; off > 0; off >>= 1) acc += __shfl_down_sync(0xffffffffu, acc, off);
  if (lane == 0) y[row] = (float) acc;
}

// The f32 siblings at the same two orders: the shipped one (gemm.cu's gemv_f32, one
// float per lane) and a float4 one, to see whether the f32 kernel is already at the
// device's bandwidth at these shapes.
extern "C" __global__ void gemv_f32_x1(const float* W, const float* x, float* y, int rows, int cols) {
  int row = (blockIdx.x * blockDim.x + threadIdx.x) >> 5;
  int lane = threadIdx.x & 31;
  if (row >= rows) return;
  const float* w = W + (long long) row * cols;
  double acc = 0.0;
  for (int j = lane; j < cols; j += 32) acc = fma((double) w[j], (double) x[j], acc);
  for (int off = 16; off > 0; off >>= 1) acc += __shfl_down_sync(0xffffffffu, acc, off);
  if (lane == 0) y[row] = (float) acc;
}

extern "C" __global__ void gemv_f32_x4(const float* W, const float* x, float* y, int rows, int cols) {
  int row = (blockIdx.x * blockDim.x + threadIdx.x) >> 5;
  int lane = threadIdx.x & 31;
  if (row >= rows) return;
  const float4* w = (const float4*) (W + (long long) row * cols);
  const float4* xv = (const float4*) x;
  double acc = 0.0;
  int quads = cols >> 2;
  for (int j = lane; j < quads; j += 32) {
    float4 a = w[j], b = xv[j];
    acc = fma((double) a.x, (double) b.x, acc);
    acc = fma((double) a.y, (double) b.y, acc);
    acc = fma((double) a.z, (double) b.z, acc);
    acc = fma((double) a.w, (double) b.w, acc);
  }
  for (int off = 16; off > 0; off >>= 1) acc += __shfl_down_sync(0xffffffffu, acc, off);
  if (lane == 0) y[row] = (float) acc;
}

// The ACCUMULATOR question, re-asked at bf16 (.todo/490): the load-width sweep above came
// out flat -- every width lands at the same ~138 GB/s -- which points at the double FMA,
// not the loads: this device's fp64 rate is a small fraction of its fp32 rate, and one
// double FMA per element is a compute bound the f32 kernel happens to sit just under. The
// compensated float-float accumulator MetalGemm uses (the product's rounding error
// recovered exactly with an fma, every addition a TwoSum, the warp fold pair-wise) runs
// at the fp32 rate and carries ~48 bits; `_ff` are those, at both widths, and `_f` is the
// plain float sum for the distance it lands from the oracle.
__device__ __forceinline__ void two_sum(float a, float b, float* s, float* e) {
  float t = a + b;
  float bp = t - a;
  *e = (a - (t - bp)) + (b - bp);
  *s = t;
}

template <typename Load>
__device__ __forceinline__ void gemv_ff(Load load, const float* x, float* y, int rows, int cols) {
  int row = (blockIdx.x * blockDim.x + threadIdx.x) >> 5;
  int lane = threadIdx.x & 31;
  if (row >= rows) return;
  float s = 0.0f, c = 0.0f;
  for (int j = lane; j < cols; j += 32) {
    float a = load(row, j), b = x[j];
    float p = a * b;
    float pe = fmaf(a, b, -p);
    float t, e;
    two_sum(s, p, &t, &e);
    s = t;
    c += e + pe;
  }
  for (int off = 16; off > 0; off >>= 1) {
    float os = __shfl_down_sync(0xffffffffu, s, off);
    float oc = __shfl_down_sync(0xffffffffu, c, off);
    float t, e;
    two_sum(s, os, &t, &e);
    s = t;
    c += e + oc;
  }
  if (lane == 0) y[row] = s + c;
}

extern "C" __global__ void gemv_bf16_ff(const unsigned short* W, const float* x, float* y, int rows, int cols) {
  gemv_ff([=] __device__(int r, int j) { return bf16f(W[(long long) r * cols + j]); }, x, y, rows, cols);
}

extern "C" __global__ void gemv_f32_ff(const float* W, const float* x, float* y, int rows, int cols) {
  gemv_ff([=] __device__(int r, int j) { return W[(long long) r * cols + j]; }, x, y, rows, cols);
}

extern "C" __global__ void gemv_bf16_f(const unsigned short* W, const float* x, float* y, int rows, int cols) {
  int row = (blockIdx.x * blockDim.x + threadIdx.x) >> 5;
  int lane = threadIdx.x & 31;
  if (row >= rows) return;
  const unsigned short* w = W + (long long) row * cols;
  float acc = 0.0f;
  for (int j = lane; j < cols; j += 32) acc += bf16f(w[j]) * x[j];
  for (int off = 16; off > 0; off >>= 1) acc += __shfl_down_sync(0xffffffffu, acc, off);
  if (lane == 0) y[row] = acc;
}
