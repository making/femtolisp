# The Q8_0 integer-dot GEMV under C2: the part-1 conversion is a `slice`, 2026-09-06

The record for `.todo/706` (closed 2026-09-06; the mechanics live in
`.kb/quantized-matrix.md`, the two JIT lessons in `.kb/vec.md`). What the item asked --
"find what C2 does with the `B2S`/`S2I` chain" -- was answered by reading the JDK, and the
kernel was reshaped around the answer: the same bits, 0.7x -> 1.9x of the f32 GEMV under
C2 on one thread, unchanged under Graal.

| what | where |
| --- | --- |
| the shapes that were built and timed, one `static` method each | `Q8Probe.java` (+ `Q8GemvBenchJit.java`), compiled against `target/classes` |
| the runner: ONE JVM PER SHAPE, both JITs, one thread | `bench-shapes.sh` |
| the shipped kernels' numbers | `../672-.../bench.sh both` (`eval/Q8GemvBench`, `codegen/jvm/Q8TemplateGemvBench`) |

## What C2 does with the chain

`Vector.convertShape(conv, species, part)` with `part != 0` is not an instruction. In
`jdk.incubator.vector.AbstractVector.convertShapeTemplate` (JDK 25) a non-zero part is
`slice(origin).convert0(kind, rsp)`, and `ByteVector.sliceTemplate` /
`ShortVector.sliceTemplate` is an iota shuffle, a compare into a mask, a second shuffle
and two `rearrange`s blended together -- Java code C2 compiles as written (a `tbl`-and-
`bsl` sequence), while Graal recognises the whole thing as the widening instruction's
upper-half form. The 2026-09-05 kernel did six of them a block (four `B2S` part 1 on the
128-bit byte loads, two `S2I` part 1 on the product vectors), and each cost ~4 cycles
under C2: at 4096x4096 the H shape below, which removes one of the two `S2I` slices, is
0.63 ms faster per GEMV over 524288 blocks = 1.2 ns a slice.

`-XX:+PrintInlining` on the old kernel under C2 also showed `q8Scale` refused with
`NodeCountInliningCutoff`: the slice machinery had eaten the compile's 18000-node budget
before the last call in the loop body. The shipped kernel inlines everything (checked the
same way).

## The shapes

All keep lane `i` = the columns `j` with `j mod 4 = i`, so all are the defun's bits
(`same-bits=true` against the shipped kernel in every run), except `R`, a ceiling probe.

| shape | the block dot |
| --- | --- |
| shipped 09-05 | 128-bit byte loads both sides, `B2S` parts 0 and 1, short mul/add, `S2I` parts 0 and 1 |
| B | 64-bit byte loads (part-0 `B2S` only), byte activation, `S2I` parts 0 and 1 |
| Bx | B with the activation pre-widened to a `short[]` once per GEMV |
| **Bs** (shipped 09-06) | Bx, the upper half brought down by a constant half-swapping `rearrange` and widened as part 0 |
| G | loads at `c` and `c+4`, only the lower four lanes widened -- reads 4 bytes past the last block, so it needs padding the file layout does not have |
| H / Hs | G for the first sixteen columns, B / Bs for the last sixteen (in bounds) |
| E | G over `ShortVector.SPECIES_64` |
| Q | the weight bytes interleaved on load (`0 4 1 5 2 6 3 7`) against an activation permuted once, so `reinterpretAsInts` + shifts split the halves with the lane definition kept |
| R | the reinterpret split without the permute: lane `i` = short lanes `2i, 2i+1`, a DIFFERENT fold |
| Bx2 / Bs2 / Bs4 | two or four rows a pass, the activation loads shared |
| Bs-u2 | Bs, two blocks an iteration |
| Bs-vh | Bs, the scale's two bytes read as one little-endian `short` through a `VarHandle` |
| Bx int-only / scale-only | the integer work alone and the scale work alone (timing only) |

## The numbers (one JVM per shape)

**Base commit `3a93b647a` + the port. NVIDIA GB10, aarch64 Cortex-X925, NEON 128-bit, 20
cores, Oracle GraalVM 25.0.4 (`-XX:-UseJVMCICompiler` for the C2 column). One thread,
4096x4096, N(0, 0.02) weights, unit gaussian activations; nine rounds of ten GEMVs after
eight warm-ups, the minimum shown; load average 0.02-1.00 / 0.30-0.55 / 0.9-1.0
across the runs (13:27-13:31), nothing else on the box.** Ratios are to the f32 GEMV timed
in the same JVM (1.91-2.18 ms, the spread of that baseline on this box).

| shape | C2 ms | C2 Gelem/s | C2 vs f32 | Graal ms | Graal Gelem/s | Graal vs f32 |
| --- | --- | --- | --- | --- | --- | --- |
| shipped 09-05 | 2.729 | 6.15 | **0.72x** | 1.343 | 12.50 | 1.46x |
| B | 5.946 | 2.82 | 0.33x (boxed, below) | 1.408 | 11.91 | 1.35x |
| Bx | 1.372 | 12.23 | 1.59x | 1.336 | 12.56 | 1.45x |
| **Bs** | **1.154-1.162** | **14.4-14.5** | **1.86-1.87x** | 1.319-1.320 | 12.7 | 1.42-1.45x |
| G | 1.504 | 11.16 | 1.45x | 1.831 | 9.16 | 1.20x |
| H | 1.378 | 12.18 | 1.43x | 1.607 | 10.44 | 1.22x |
| Hs | 1.300 | 12.91 | 1.51x | 1.557 | 10.78 | 1.18x |
| E | 1.435 | 11.69 | 1.54x (unstable) | 1.807 | 9.28 | 1.05x |
| Q | 1.131 | 14.83 | 1.73x | 1.418 | 11.83 | 1.33x |
| R (other fold) | 1.019 | 16.46 | 1.92x | 1.309 | 12.82 | 1.50x |
| Bx2 | 19.5 | 0.86 | 0.10x (boxed) | 1.186 | 14.15 | 1.63x |
| Bs2 | 11.6 | 1.45 | 0.17x (boxed) | 1.185 | 14.16 | 1.56x |
| Bs4 | 26.3 | 0.64 | 0.08x (boxed) | 1.244 | 13.49 | 1.51x |
| Bs-u2 | 15.6 | 1.08 | 0.13x (boxed) | 1.333 | 12.58 | 1.62x |
| Bs-vh | 1.173 | 14.30 | 1.85x | 1.282 | 13.09 | 1.69x (noise: the f32 baseline moved) |
| Bx int-only | 0.852 | 19.70 | -- | 0.970 | 17.30 | -- |
| scale-only | 0.434 | 38.6 | -- | 0.515 | 32.6 | -- |

What the table says:

- **Removing the slices is the whole C2 win.** Bx (four slices fewer) 0.72x -> 1.59x; Bs
  (all six gone) 1.87x; R, which also has none, 1.92x. Under Graal every slice-free shape
  with the same instruction count sits at 1.42-1.50x, exactly where the sliced kernel
  sat: Graal was already emitting the upper-half widen.
- **Graal is instruction-bound at ~9 cycles a block, and the parts add.** Integer work
  alone 6.5 cycles a block (17.3 Gelem/s), the scale chain alone 3.5 (32.6 Gelem/s),
  together 9 (12.6 Gelem/s). A shape with MORE instructions (G, H, E: eight weight loads
  and casts a block instead of four) is slower under Graal in proportion, however
  slice-free it is. The only Graal gain found is sharing the activation loads across
  rows (Bx2 / Bs2: 1.56-1.63x), which C2 cannot compile -- next bullet.
- **C2's compile has a size budget and a kernel must fit it.** Two rows a pass, four rows,
  or two blocks an iteration all overran `NodeCountInliningCutoff` (18000 nodes; the
  Vector API expands to hundreds of IR nodes per call before folding) and the LAST calls
  of the loop body were refused inlining -- `q8Lower`, `step`, `finish` in
  `-XX:+PrintInlining` -- so their vectors were boxed: 0.08-0.17x. The budget is a stock
  JDK's default and a compiled `.class` cannot change it. One row, one block an iteration
  is the size that fits with room (the shipped kernel's compile inlines `q8Scale` again).
- **A shape's number is only its own in its own JVM.** The first probe run timed every
  shape in one JVM and B measured 1.04x, then 0.33x in the next run, then 0.33x alone:
  the shapes share helpers (`s8`, `lo`, `step`), C2 compiles a helper standalone once it
  is hot in one shape, and a callee whose standalone code exceeds `InlineSmallCode`
  (1000 bytes on aarch64; a boxed Vector API method is far larger) is refused inlining
  into the next shape's loop ("already compiled into a big method"). `bench-shapes.sh`
  runs one JVM per shape for that reason, and the shipped kernels' helpers are private to
  their one caller.
- The `VarHandle` scale read (Bs-vh) buys nothing measurable on either JIT; the two byte
  loads stay.

Also measured, clean JVMs, shipped 09-05 -> Bs: **1024x1024 C2 0.28x -> 0.65x, Graal 0.68x
-> 0.67x; 5632x2048 C2 0.62x -> 1.45x, Graal 1.15x -> 1.18x; 288x288 C2 0.24x -> 0.42x,
Graal 0.59x -> 0.54x.** The cache-resident shapes still lose to f32 on both JITs, as
`.kb/quantized-matrix.md` says and for the reason `.kb/bfloat16.md` gives: there is no
faster bit-identical thing to gate to.

## The shipped kernels, both JITs, both copies

`../672-.../bench.sh both`, two full passes (13:38:00-13:38:25; load average 0.83 / 0.56 /
0.75 before, 1.07 / 0.64 / 0.78 after), same box, same base commit, `RONTOLISP_THREADS`
unset = 20 threads. `q8 kernel == defun: true` at every shape under both JITs. The
`q8 sliced widens (probe)` row is the 09-05 kernel kept in `Q8GemvBench`, timed in the
same JVM as the shipped one.

`eval.VecSimdKernels`, one thread, ms per GEMV and the ratio to the shipped f32 GEMV, the
two passes as a range:

| shape | Graal f32 | Graal q8 (shipped) | Graal q8 (09-05 probe) | C2 f32 | C2 q8 (shipped) | C2 q8 (09-05 probe) |
| --- | --- | --- | --- | --- | --- | --- |
| 288x288 | 0.006 | 0.57x | 0.37-0.64x | 0.004 | 0.44-0.45x | 0.25x |
| 1024x1024 | 0.063 | 0.73-0.75x | 0.71x | 0.054-0.056 | 0.75-0.76x | 0.31x |
| 4096x4096 | 1.85-2.10 | **1.42-1.57x** (12.6-12.9 Gelem/s) | 1.36-1.54x | 1.95-2.19 | **1.72-1.91x** (14.6-14.8 Gelem/s) | **0.68-0.78x** (5.9-6.0) |
| 5632x2048 | 1.22-1.32 | 1.31-1.43x | 1.29-1.41x | 1.17-1.32 | 1.38-1.68x | 0.59-0.69x |

`codegen.jvm.JvmSimdVectorTemplate` (the copy in every `--simd` `.class`, through the real
bridge entries over headered arrays): Graal 1.52-1.61x / C2 1.75-1.90x at 4096x4096, Graal
1.42-1.45x / C2 1.47-1.64x at 5632x2048, C2 0.72-0.78x at 1024x1024 and 0.46-0.52x at
288x288 -- within ~0.1x of the eval twin at every cell, as before.

For comparison, the fused bf16 GEMV in the same runs: Graal 1.31-1.45x, C2 1.76-2.00x at
4096x4096. Under C2 the Q8_0 kernel is now level with it (it was 0.4x of it); under Graal
it is above it, as it was.

`--parallel`, 20 threads, the two passes as a range (this column is shape-peaked and moves
0.5x between passes -- `.todo/702` -- so it is a direction, not a rate):

| kernels, shape | Graal q8 vs f32 | Graal q8 Gelem/s | C2 q8 vs f32 | C2 q8 Gelem/s |
| --- | --- | --- | --- | --- |
| eval, 4096x4096 | 2.18-2.66x | 91-96 | 1.59-2.52x | 58-86 |
| eval, 5632x2048 | 2.23-3.26x | 94-120 | 2.28-2.39x | 85 |
| template, 4096x4096 | 1.76-2.39x | 63-82 | 2.19-2.27x | 86 |
| template, 5632x2048 | 2.18-2.29x | 94 | 2.06-2.07x | 85 |

Relative error against an f64 GEMV, unchanged (the bits are): Q8_0 7.5e-3 .. 7.8e-3, bf16
1.5e-3 .. 1.7e-3, f32 1e-7 .. 3e-7.

## What was not done, and why

- **Two rows a pass** is a 10% Graal gain that boxes under C2 (above). A kernel that is
  fast under one JIT and boxed under the other is not done (`.kb/vec.md`), and the JIT
  cannot be detected from a compiled `.class` reliably enough to pick a shape by it.
- **The scale chain** (a third of the block under both JITs: two byte loads, `fcvt` h->s,
  `fcvt` s->d, `fmul`, `fcvt` d->s, `dup`) is pinned by the defun's `p = (float) (sw *
  sx)` and cannot be vectorised across blocks -- the scales sit at a 34-byte stride, and
  the Vector API has no binary16 conversion, so the f16 widen is scalar whatever the
  layout.
- **An int8 dot instruction** (`SDOT`) is still what ggml has and the JDK does not
  expose; the gap to it is now the instruction count of the widen-multiply-widen chain
  (R's 16.5 Gelem/s under C2 is the ceiling of this API's shapes on this box), not a JIT
  cliff.

```bash
./mvnw -o test-compile
.todo/artefacts/706-the-q8-0-integer-dot-gemv-is-instruction-bound-on-one-thread/bench-shapes.sh
.todo/artefacts/672-a-q8-0-quantized-weight-matrix-and-its-integer-dot-gemv/bench.sh both
```
