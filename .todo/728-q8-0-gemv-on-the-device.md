# 728. Q8_0 GEMV on the device: the width that already exists, at 0.5-0.58 of the bf16 kernel time

Difficulty: High

Filed 2026-09-07 by `.todo/726`, which re-took the Q4-on-the-device refusal with a measurement
(`.kb/gpu.md`, "No Q4_0 / Q4_K weight width"; `.todo/artefacts/123-gpu-acceleration/README.md`,
"The Q4 ceiling, measured") and found the cheaper width in front of it.

## What was measured

GB10, Qwen3.5-0.8B, JVM class output `--gpu --simd`, one thread. The forward is 16.6-16.9 ms at
`-w bf16` and is LINEAR in the GEMV kernel time (22.0-23.7 at `-w f32`: twice the bytes, 5.1-7.1
ms for a kernel difference of 5.5-6.3). A Q8_0 GEMV kernel over ggml's own 34-byte blocks
(`gemv-q4-probe.cu`, `gemv_q8_0_dp4` / `_l2`: the integer-dot shape the CPU kernel computes, x
quantized to int8 per block) runs at 0.50-0.58 of the shipped `gemv_bf16`'s time a forward, cold
from DRAM at this model's seven shapes -- **2.7-3.4 ms off a 16.7 ms forward, 1.20-1.25x** -- and
the width's model-side error is the 7.6e-3 of `.todo/672`, not Q4_0's 8.5%.

Today `--gpu` over the publisher's `Qwen3.5-0.8B-Q8_0.gguf` runs, and declines EVERY GEMV to the
CPU lane kernel (`LinalgGpu` / `compileMatvecChain` decline a `byte[]`, `.kb/quantized-matrix.md`,
"Refusals"), so the device arm of that file is the CPU arm.

## Do

Put `vec:matvec` over a `rontolisp:quantized-matrix` (Q8_0) against an `#f` vector on the CUDA
device, the way bf16 joined (`.kb/gpu.md`, "bfloat16 is the third matrix width of this member"):

- `gemm.cu`: `gemv_q8_0` over the ggml layout as stored -- the `byte[]`'s data offset is
  `8 + 4 * rank` (`.kb/quantized-matrix.md`, "JVM representation"), and the kernel must not
  assume 4-byte alignment of a block (34-byte stride). The probe's `_dp4` shape (four lanes a
  block, `__dp4a` over 16-bit word pairs, x as `[nb float scales][cols int8]`) was the fastest;
  the activation quantization is the CPU contract's (`amax / 127` in double, `rint`), done once a
  call on the host or in a tiny kernel. `gemm.metal`: out of scope, a hard decline like bf16.
- The precision pin is a DESIGN QUESTION to settle first: the CPU contract is defun == kernel BIT
  FOR BIT with four f32 lane accumulators walked sequentially over blocks and folded
  `(acc0 + acc2) + (acc1 + acc3)`. A warp over blocks folds in another order; either the device
  kernel keeps four threads a row walking the blocks in the defun's order (parallelism = 4 x rows;
  measure whether the 1024-row shapes still stream), or the device joins the f32 row's pin -- a
  relative tolerance plus ">99% of rows identical" -- and `.kb/quantized-matrix.md`'s invariant
  gains the `--gpu` exception explicitly. llm's story must stay byte-identical with the flag on
  or the divergence must be recorded as `.todo/672` recorded ggml's.
- `Gpu.matvec(byte[], float[])` + `GpuDevice.supportsQuantized()` beside `supportsBfloat16()`;
  `DeviceResidency` keys a `byte[]` (width 1; never a result, never a stub); the two-sight rule
  as for every matrix; the element threshold counts ELEMENTS, not bytes.
- Interpreter arm: `LinalgGpu.matvec`'s `LispQuantizedMatrix` case before the `instanceof
  LispFloatArray` decline. JVM: `JvmGpuTemplate.gpuMatvecQ8` reading `qmOff/qmDim`, wired into
  `compileMatvecChain`'s device rung ahead of the `QUANTIZED_OPERAND` lane-width arm.
- `GpuTest` (kernel against the CPU kernel at the crossover shapes), the `--gpu` row of
  `ci-spec`'s `quantized-matrix` case if the pin allows it, and `examples/llm/README.md`'s device
  table with a `Q8_0` row.

## Done

- `--gpu -w` over the Q8_0 GGUF decodes faster than the bf16 file on the device arm (measured
  ceiling 13.5 ms a forward against 16.7), with the tokens recorded.
- `.kb/gpu.md`'s Q4 bullet is re-taken against the measured increment (1.4-1.8 ms a forward).

## Not in scope

Q4_0 / Q4_K (refused in `.kb/gpu.md` with this item as its trigger); Metal; the CPU kernel.
