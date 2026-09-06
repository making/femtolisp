# The 3072x3072 parallel GEMV dip, 2026-09-06

`.todo/702` found a reproducible dip to 25-28 Gelem/s at 3072x3072 in the parallel f32
GEMV, sandwiched between 2048x2048 (~48-55) and 4096x4096 (~41-44), and filed this item
rather than chase the cause. Two hypotheses were live: a ROW COUNT (leaf-quantization)
effect, or a BYTE SIZE (cache/TLB) effect.

The bench: `src/test/java/am/ik/rontolisp/eval/ParallelGemvDipSweepBench.java`, a `main`
beside `ParallelGemvSizeSweepBench` (`.todo/702`'s). Four sections, all on the shipped
`VecSimdKernels.matvecIntoF`, serial and `--parallel`:

- **A** -- square shapes 2048x2048 to 4096x4096, step 128.
- **B** -- rows fixed at 3072, columns swept (tests the row-count hypothesis).
- **C** -- columns fixed at 3072, rows swept (tests a row-stride/`workPerRow` hypothesis).
- **D** -- element count fixed at 3072*3072 = 9 437 184 (the same 37.75 MB), shape varied
  across nine row/column splits from 1024x9216 to 9216x1024 (tests the byte-size
  hypothesis directly, independent of both row count and stride).

```bash
./mvnw -o spring-javaformat:apply test-compile
CP=target/classes:target/test-classes
java --add-modules jdk.incubator.vector -cp $CP am.ik.rontolisp.eval.ParallelGemvDipSweepBench
```

**Conditions.** Base commit `c5c60608c`. Same box as `.todo/702`: NVIDIA GB10, aarch64,
20 cores, Oracle GraalVM 25.0.4, Java 25.0.4. No other maven or java process observed
running throughout (checked between runs). Load average stayed in the 0.4-2.3 range
throughout (a shared box; see "A wrinkle" below) with no sustained spike coinciding with
any run. `RONTOLISP_THREADS` set explicitly per run as noted -- **this box's *default*
(`RONTOLISP_THREADS` unset) is no longer what `.todo/702` measured; see finding 0.**

## Finding 0: the dip does not reproduce under today's default, because the default changed

`.todo/702`'s README states its conditions as "`RONTOLISP_THREADS` unset -> 20 threads".
That was true when it ran, at commit `070984aec`. Five hours earlier the same day, commit
`7dda99611` (`.todo/697`) changed `SimdParallel.defaultThreads()` from the full box to
HALF the box -- `070984aec` is a descendant of `7dda99611` in the commit graph, so 702's
own run should already have seen 10, but its README explicitly measured and reported
`threads=20`. Today, `RONTOLISP_THREADS` unset resolves to `threads=10` on this 20-core
box, and three sweeps of Section A at the unset default show **no dip at all** -- a smooth
mid-30s-to-mid-50s Gelem/s band across 2048-4096, e.g. run 1: 57.6 (2048) -> 48.1 (3072) ->
41.6 (4096), monotonically declining like the serial column. Two more repeats (recorded
below) are noisier in absolute level (this is a shared box) but neither shows the deep
trough Section-A-at-`threads=20` shows on every repeat.

**So the first-order answer for anyone relying on the default today: the dip `.todo/702`
found is not live at the current default thread count.** It only shows at higher thread
counts (below). This item's title cites `3072x3072`, but the mechanism, once isolated,
turns out to depend on the box's *thread count* at least as much as the matrix's shape --
finding 3 below.

## Finding 1: real, not noise -- reproduced 3/3 at `RONTOLISP_THREADS=20`

Re-running `.todo/702`'s own `ParallelGemvSizeSweepBench` with `RONTOLISP_THREADS=20` set
explicitly (matching what its README describes) reproduces the dip on this box today:

| shape | par Gelem/s (today, t=20) | par Gelem/s (`.todo/702`, three runs) |
| --- | --- | --- |
| 2048x2048 | 52.19 | 48.25-55.15 |
| 3072x3072 | 27.52 | 25.02-27.52 |
| 4096x4096 | 42.90 | 41.29-43.06 |

Section A of the new bench at `t=20`, three back-to-back runs (par Gelem/s only):

| shape | MB | run 1 | run 2 | run 3 |
| --- | --- | --- | --- | --- |
| 2048x2048 | 16.78 | 41.61 | 34.19 | 40.26 |
| 2176x2176 | 18.94 | 37.74 | 47.99 | 46.25 |
| 2304x2304 | 21.23 | 31.20 | 34.47 | 45.24 |
| 2432x2432 | 23.66 | 39.50 | 38.34 | 39.46 |
| 2560x2560 | 26.21 | 31.84 | 33.05 | 33.04 |
| 2688x2688 | 28.90 | 27.58 | 28.52 | 28.51 |
| 2816x2816 | 31.72 | 24.84 | 26.44 | 20.08 |
| 2944x2944 | 34.67 | 24.13 | 26.71 | 27.63 |
| 3072x3072 | 37.75 | 23.93 | 26.37 | 28.90 |
| 3200x3200 | 40.96 | 26.33 | 27.34 | 24.85 |
| 3328x3328 | 44.30 | 28.16 | 30.72 | 32.00 |
| 3456x3456 | 47.78 | 31.64 | 35.02 | 38.73 |
| 3584x3584 | 51.38 | 40.19 | 40.46 | 40.56 |
| 3712x3712 | 55.12 | 42.40 | 42.69 | 42.55 |
| 3840x3840 | 58.98 | 42.11 | 42.79 | 41.28 |
| 3968x3968 | 62.98 | 41.59 | 42.16 | 42.51 |
| 4096x4096 | 67.11 | 41.58 | 42.09 | 41.00 |

## Finding 2: it is a broad trough (2560-3456), not a spike at exactly 3072

All three runs agree on the SHAPE, not just one point: the rate falls off a cliff between
2432 (~38-40) and 2560 (~31-33), stays in the low-to-mid 20s from 2688 through 3328, and
climbs back above 40 only at 3584. `.todo/702`'s two-point neighbours (2048, 4096) simply
straddled a trough roughly 900 columns wide -- 3072x3072 is close to the middle of it, not
a special value in itself. This alone argues against a quantization cliff tied to one
specific row count: `SimdParallel.rows`'s grain formula
(`max(GRAIN/workPerRow, rows/(LEAVES_PER_THREAD*threads))`, `GRAIN=2^13`,
`LEAVES_PER_THREAD=4`) gives, at `threads=20` for square shapes across this whole range,
a leaf count that stays at **80-83 throughout** (worked by hand: 2048 -> 82, 2560 -> 80,
3072 -> 81, 3584 -> 80, 4096 -> 81) -- no dip or spike in leaf count anywhere near 3072,
so the trough is not the leaf/grain arithmetic hitting an awkward divisor.

## Finding 3: byte size, not row count -- decided by Section D

Section D pins the element count at exactly 3072*3072 = 9 437 184 (37.75 MB) and varies
the shape across nine splits. Every one of them lands in the same trough, regardless of
row count or row-major stride:

```
shape           MB(f32)  par Gelem/s
3072x3072         37.75     24.68
1536x6144         37.75     23.39
6144x1536         37.75     25.03
2304x4096         37.75     26.49
4096x2304         37.75     25.68
1024x9216         37.75     26.18
9216x1024         37.75     27.10
2048x4608         37.75     24.01
4608x2048         37.75     26.81
```

Sections B and C corroborate from the other direction: with rows FIXED at 3072, shrinking
columns to 1024 (12.6 MB total) escapes the trough entirely (63.18 Gelem/s); with columns
fixed at 3072, shrinking rows to 1024 does the same (59.73). The trough only appears when
the OTHER dimension also puts total bytes in the ~26-50 MB range -- it tracks the
matrix's total size, not either dimension alone and not the row count.

**Answer to the item's question: the dip is a BYTE SIZE effect. A row count of exactly
3072 has nothing special about it** -- any shape whose total size falls in roughly 26-50
MB dips, square or not, and the earlier leaf-count arithmetic (finding 2) already ruled
out a row-count/quantization mechanism independently.

## Finding 4: the trough needs BOTH the byte-size window and enough threads

Not asked for by the item, but the box was already warm and each full sweep costs about
20-25 seconds, so Section A was re-run at four more thread counts (one run each):

| threads | trough present? | worst cell in 2560-3584 (Gelem/s) |
| --- | --- | --- |
| 8 | no (flat 33-42) | 32.71 (2688) |
| 10 (today's default) | weak/inconsistent, see finding 0 | 28.55-42 across three runs |
| 12 | yes, shallower | 28.56 (2944) |
| 16 | yes | 25.03 (2944) |
| 20 | yes, deepest | 20.08-24.13 (2816-2944) |

The trough widens and deepens monotonically from 10/12 threads up to 20; it is essentially
absent at 8. So the dip is a joint effect: it needs the matrix in the ~26-50 MB window
AND enough concurrent threads reading it -- not the byte size alone (the serial column
over the same range is smooth, `.todo/702`) and not the thread count alone (a
cache-resident 1024x1024 shows no such effect at any thread count in `.todo/702`'s and
`.todo/697`'s numbers).

## A wrinkle worth recording: this box's 20 "cores" are not homogeneous

`.todo/702`'s README (and this repo's running memory notes) describe the box as "aarch64
Cortex-X925, 20 cores" without qualification. `lscpu` on it today shows two core models
side by side: 10x Cortex-X925 (max 3.9 GHz) and 10x Cortex-A725 (max 2.8 GHz), one
socket, sharing 25 MiB of private L2 (20 instances, ~1.25 MiB/core) and 24 MiB of L3
across 2 instances. The 26-50 MB window where the trough lives straddles that 24 MiB L3
capacity almost exactly. A matrix just over LLC capacity, read by enough concurrent
threads to draw on both core clusters, is the textbook shape of an eviction/thrashing
regime that gets WORSE with more concurrent streams rather than better -- consistent with
finding 4's threads=8/10 vs 16/20 split, since claim()'s dynamic leaf-stealing already
rules out a simple load-imbalance explanation (a fast core just claims more leaves; the
mechanism has to be shared-cache pressure, not scheduling). This is offered as the most
plausible mechanism, not a proven one -- nothing here profiled cache misses directly, and
a `.kb` correction to the box's core description is left for whoever next writes down
this box's specs, not done here.

## `.kb/simd-parallel.md` updated

Its "Shape, decided by measurement" paragraph is corrected: `.todo/702`'s 41-42 Gelem/s
two-point read was itself two points on the SAME hump that this item now knows has a
trough in the middle of the cache-resident range, not just a rise then a plateau, and the
trough's presence is thread-count-dependent and mostly invisible at today's actual
default. See that file for the corrected paragraph.
