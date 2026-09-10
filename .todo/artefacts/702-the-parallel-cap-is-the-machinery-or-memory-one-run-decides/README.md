# The parallel f32 GEMV size sweep, 2026-09-06

The bench: `src/test/java/am/ik/rontolisp/eval/ParallelGemvSizeSweepBench.java`, a
`main` beside `Bf16GemvBench` (surefire's naming patterns skip it). No bf16, no model --
only the shipped `VecSimdKernels.matvecIntoF`, serial and `--parallel`, over square
shapes from 256x256 (0.26 MB f32, unambiguously cache-resident) up through 4096x4096
(67 MB, `.todo/488`'s largest cell).

```bash
./mvnw -o spring-javaformat:apply test-compile
CP=target/classes:target/test-classes
java --add-modules jdk.incubator.vector -cp $CP am.ik.rontolisp.eval.ParallelGemvSizeSweepBench
```

**Conditions.** Base commit `00badd09`. NVIDIA GB10, aarch64 Cortex-X925, 20 cores, NEON
128-bit, Oracle GraalVM 25.0.4 (`jit=graal` printed by the harness), Java 25.0.4.
`RONTOLISP_THREADS` unset -> 20 threads (the calling thread included). No other maven or
java process running (`pgrep -af maven` / `pgrep -af mvn` empty throughout). Load average
immediately before run 1: 1.02, 0.97, 1.53; before run 2: 0.79, 0.92, 1.50; before run 3:
0.46, 0.82, 1.44 -- three back-to-back runs, `.todo/488`'s "one run per cell is not
enough in the parallel column" rule.

## The three runs (par Gelem/s only; full tables below)

| shape | MB (f32) | run 1 | run 2 | run 3 |
| --- | --- | --- | --- | --- |
| 256x256 | 0.26 | 13.12 | 13.12 | 13.14 |
| 384x384 | 0.59 | 24.73 | 29.44 | 32.17 |
| 512x512 | 1.05 | 35.80 | 34.56 | 36.36 |
| 768x768 | 2.36 | 46.18 | 44.37 | 44.71 |
| 1024x1024 | 4.19 | 60.14 | 58.06 | 64.29 |
| 1536x1536 | 9.44 | 59.03 | 68.99 | 67.47 |
| 2048x2048 | 16.78 | 54.63 | 48.25 | 55.15 |
| 3072x3072 | 37.75 | 25.71 | 25.02 | 27.52 |
| 4096x4096 | 67.11 | 43.06 | 41.29 | 41.88 |

Run 1, full table (runs 2 and 3 agree to the same shape):

```
shape           MB(f32)  serial ms     par ms   serial G  par Gelem  speedup
256x256            0.26     0.0044     0.0050      14.73      13.12    0.89x
384x384            0.59     0.0097     0.0060      15.14      24.73    1.63x
512x512            1.05     0.0165     0.0073      15.86      35.80    2.26x
768x768            2.36     0.0371     0.0128      15.92      46.18    2.90x
1024x1024          4.19     0.0616     0.0174      17.03      60.14    3.53x
1536x1536          9.44     0.1448     0.0400      16.30      59.03    3.62x
2048x2048         16.78     0.3299     0.0768      12.71      54.63    4.30x
3072x3072         37.75     0.8664     0.3670      10.89      25.71    2.36x
4096x4096         67.11     1.7329     0.3896       9.68      43.06    4.45x
```

Load average immediately after run 3: 0.9x range, no other process observed running
throughout (checked between runs).

## The finding: neither predicted outcome -- there is no plateau at all

`.todo/702`'s two outcomes were binary: still at 41-42 Gelem/s when cache-resident (the
machinery caps it) or clearly above 41-42 at 256x256, falling to it as the matrix leaves
cache (memory caps it). **What the sweep shows is a hump, not a step**, and it rules out
"41-42 Gelem/s is this box's parallel ceiling" as ever having been a real ceiling:

- **256x256 sits BELOW 41-42, not at or above it** -- 13.1 Gelem/s, and the `--parallel`
  arm is *slower than the serial kernel* here (0.89x). This has an exact mechanical
  cause, not a guess: `SimdParallel.rows`'s grain formula is
  `max(GRAIN/workPerRow, rows/(LEAVES_PER_THREAD*threads))` = `max(8192/256, 256/80)` =
  `max(32, 3)` = 32 rows/leaf, so a 256-row call cuts into only **8 leaves for 20
  threads** -- twelve of the twenty never run a single leaf, and the call pays full
  wake/spin/join overhead for barely more parallelism than two threads' worth of work.
  This is squarely the "machinery" category the item names (work distribution,
  per-row dispatch): a small-shape row count that does not divide into enough leaves to
  reach every thread.
- **1024x1024 through 2048x2048 sit WELL ABOVE 41-42** -- 48-69 Gelem/s, up to 1.65x the
  number `.todo/488` called "this box's ceiling and not a kernel property". A number
  supposedly bounded by DRAM bandwidth cannot be exceeded by 65% at a larger shape than
  the one it was measured at; 4.19-16.78 MB is still resident in whatever cache level
  this part shares across cores, so this range is cache-bandwidth-bound, not
  DRAM-bandwidth-bound, and well-subscribed (80-86 leaves for 20 threads, close to the
  `LEAVES_PER_THREAD=4` design point) -- both machinery and memory are doing well here,
  which is exactly why it is the fastest part of the curve.
- **3072x3072 dips hard to 25-28 Gelem/s**, well below both its neighbours (2048's ~54 and
  4096's ~42) and reproducible across all three runs to within 2.5 Gelem/s. 37.75 MB is
  already larger than 2048's 16.78 MB and should therefore fall smoothly toward 4096's
  regime, not undershoot it. Cause not established by this sweep -- filed as
  `.todo/713` rather than chased here.
- **4096x4096 lands back at 41-44 Gelem/s**, matching `.todo/488`'s figure at this one
  shape. That is the coincidence the size sweep breaks: 1024x1024 (4.19 MB, comfortably
  cache-resident by every argument in `.todo/488`) and 4096x4096 (67 MB, certainly not)
  landing on the "same" 41-42 in that item's two-shape measurement was two different
  regimes (cache bandwidth peaking near 2x higher, DRAM bandwidth further down) that
  happened to average out to a similar number at exactly those two points -- not one
  ceiling binding both.

**The serial column, by contrast, is smooth and monotonic** (14.6-17.0 Gelem/s at
256-1536, declining steadily to 9.4-9.9 at 3072-4096 as the working set leaves the
single core's cache) -- exactly the shape a bandwidth/cache-capacity story predicts. The
parallel column's non-monotonic hump-then-dip-then-recover shape, which the serial
column does not share, is itself the evidence that something other than a smooth
memory relationship governs it: the parallel dispatch machinery's leaf/grain
quantization against the thread count, layered on top of the cache/DRAM boundary the
serial column already shows.

## Answer to the two follow-on effects this was filed to settle

- **GB10's two-shape observation (`41-42 at both 1024x1024 and 4096x4096`) does not
  generalize to "the parallel machinery's own ceiling" OR to "a flat DRAM ceiling".**
  It was two points on a hump that happen to sit near each other. A width or kernel
  change that halves bytes moved will still buy real speed in parallel **in the
  cache-resident range** (1024-2048, this run) and **at truly out-of-cache sizes**
  (4096, where 41-44 does look like a genuine DRAM ceiling, consistent with the 164-176
  GB/s range `.todo/488` computed from it) -- so `.todo/672`'s Q8_0, `.todo/490`'s device
  work and `.todo/489`'s width predictions are NOT foreclosed by "the parallel arm is
  machinery-capped everywhere". The one size where a narrower width buys nothing in
  parallel is a **small, undersubscribed** shape like 256x256, where dispatch overhead
  dominates regardless of what is being multiplied -- a machinery cap, but a local one,
  not a global one.
- **`dorian`'s per-model scaling curves (`examples/llm/README.md`, the per-width table) are not explained away by this
  result.** This sweep is one kernel at one box; it shows the parallel rate is
  shape-dependent even within ONE box's cache hierarchy, which if anything makes it more
  plausible (not less) that dorian's differing 1->32 thread scaling per model
  (the same table) reflects real differences in how each model's matvec shapes interact
  with dorian's own cache/thread-count arithmetic, rather than one property of "how the
  model streams" or one property of "how the work was cut up" -- both are live, and
  which dominates for a given model's shape is exactly as answerable as this item's
  question was, by the same kind of sweep on dorian's shapes.

## `.kb/simd-parallel.md` updated

Its "Shape, decided by measurement" section stated `ceiling is memory bandwidth`
unqualified; that line is now dated and scoped to the regime this sweep shows it
actually holds in (out-of-cache shapes only), with a pointer here.
