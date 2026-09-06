# 713. The 3072x3072 parallel GEMV dip `.todo/702` left open

Difficulty: Low (one more sweep at fine granularity, on a cleared box; no design work)

Filed 2026-09-06 out of `.todo/702`'s finding, so it has an owner instead of only a
footnote in that item's README.

## What was seen

`.todo/702`'s size sweep of the parallel f32 GEMV (`ParallelGemvSizeSweepBench`, GB10,
`RONTOLISP_THREADS` unset -> 20 threads, GraalVM 25.0.4, base commit `00badd09`) found a
hump, not a flat ceiling: 13 Gelem/s at 256x256, rising to 48-69 Gelem/s through
1024x1024-2048x2048, then a reproducible dip to **25-28 Gelem/s at 3072x3072** -- well
below both its neighbours (2048x2048's ~54 and 4096x4096's ~42) -- before recovering to
41-44 at 4096x4096. Three back-to-back runs agreed on the dip to within 2.5 Gelem/s, so
it is not run-to-run noise.

The serial column over the same shapes is smooth and monotonically declining (16.4 at
1536x1536 down to 9.4-9.9 at 3072x3072-4096x4096), so whatever causes the parallel dip is
specific to the parallel path -- not a simple consequence of the matrix leaving cache,
which the serial numbers already show happening smoothly across that range.

## Do

Sweep more finely between 2048x2048 and 4096x4096 (say every 128 or 256 columns) with the
same harness (`src/test/java/am/ik/rontolisp/eval/ParallelGemvSizeSweepBench.java`,
`.todo/702`'s), on a cleared box, load average recorded before and after, more than one
run per cell. Two things worth checking specifically, from `SimdParallel.rows`'s grain
formula (`max(GRAIN/workPerRow, rows/(LEAVES_PER_THREAD*threads))`):

- Whether the dip tracks a specific ROW COUNT (a leaf/grain quantization effect -- some
  row counts divide into an awkward number of leaves for 20 threads) or a specific BYTE
  SIZE (a cache- or TLB-associativity effect from the row stride, e.g. page-coloring
  conflicts at particular power-of-two-adjacent strides).
- Whether non-square shapes at the same row count or the same byte size reproduce it,
  which would separate the two hypotheses cleanly.

## Why it is worth a lane rather than a footnote

`.todo/702` already changed the working model of the parallel arm's rate from "one
ceiling" to "shape-dependent, hump-shaped" -- every current and future width prediction
that assumes a flat parallel ceiling (`.todo/672`, `.todo/490`, `.todo/489`) now has a
concrete counter-example shape to check itself against, and a still-unexplained dip in
the middle of the curve is exactly the kind of detail a shape-dependent model needs
before it can be trusted to predict a size nobody has measured yet.
