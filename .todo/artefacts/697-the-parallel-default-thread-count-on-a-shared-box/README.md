# `--parallel`'s default thread count, 2026-09-06

`.todo/697` opened on a pair of numbers taken on dorian on 2026-09-05: the
`examples/llm` decode loop at 0.62 tok/s under the default 64 threads against 9.88 at
`RONTOLISP_THREADS=32`, with a six-core build running beside it. The item asked for three
things -- pin the pathology, decide the default by measurement, re-measure the README rows
-- and this is what the measurements said.

**Conditions.** Base commit `24d4dd80`, dorian (2 sockets x 16 cores x 2 threads = 64
hardware threads, Xeon E5-2697A v4, Broadwell/AVX2), Oracle GraalVM 25.0.4, `tsc`
clocksource, 251 GB RAM. The decode arm is the JVM class output of `examples/llm/llm.lisp`
compiled `--simd --parallel`, run as `java -Xmx24g --add-modules jdk.incubator.vector Llama
Qwen3-0.6B-BF16.gguf -t 0 -n 64 -i "Once upon a time"` (f32 weights), one run per cell
unless a cell says otherwise. The kernel arm is
`src/test/java/am/ik/rontolisp/eval/ParallelContentionBench.java`, a `main` beside
`ParallelGemvSizeSweepBench`: the shipped `VecSimdKernels.matvecIntoF` parallel arm at
1024x1024 f32 in a decode-shaped loop (a 100 us scalar gap between calls), reported as the
MEAN over nine rounds -- a best-of-N summary hides exactly the stall being measured. No
other rontolisp lane on the box; its steady co-tenants keep the idle load average at
0.2-0.9.

```bash
./mvnw -o spring-javaformat:apply test-compile
CP=target/classes:target/test-classes
RONTOLISP_THREADS=64 java --add-modules jdk.incubator.vector -cp $CP \
  am.ik.rontolisp.eval.ParallelContentionBench 1024 <busy-threads> 100 9
```

## 1. The pathology is real, and it is a tail

It reproduced ONCE, and only from two copies of the decode loop started five seconds
apart, 64 threads each:

| what ran | tok/s |
| --- | --- |
| one run, 64 threads, idle box | 8.98 / 8.70 / 8.76 |
| **two runs, 64 threads each** | **0.57 and 3.38** |
| two runs, 64 threads each (three later repeats) | 8.60/8.44, 8.04/8.51 |
| two runs, the new default (32) each | 8.75 / 9.43 |
| one run, 64 threads, 6 / 16 / 64 pure-CPU spinner threads beside it | 8.68 / 8.23 / 7.00 |
| one run, 64 threads, a `./mvnw clean package` beside it | 8.76 |

So the mechanism the item names -- a worker descheduled while it holds a leaf, with the
caller spinning until that leaf comes back -- is real, but "one busy core costs 10x" is
not the shape of it. A busy core is not enough; it takes a second program that is itself
filling the box, and even then it hit once in four attempts. **Do not expect a bad number
on demand**, and do not read a single good number as a fix.

## 2. The default: half the processors

The decode loop, idle box, one run per count:

| threads | 64 | 32 | 16 | 8 | 4 | 1 |
| --- | --- | --- | --- | --- | --- | --- |
| tok/s | 8.70 | 9.45 | 9.55 | 7.58 | 5.50 | 2.27 |

The curve is flat from 16 to 32 and **bends down at 64**: the whole-machine count is not
merely exposed, it is slower on an idle box than half of it. The kernel bench says the
same at every level of outside load (mean ms/call, lower is better; the shipped code, no
change):

| busy threads beside it | 64 threads | 32 threads | 16 threads |
| --- | --- | --- | --- |
| 0 | 0.041 / 0.047 / 0.053 | 0.040 / 0.042 / 0.046 | 0.027 / 0.031 / 0.032 |
| 16 | 0.046 / 0.058 / 0.066 | 0.038 / 0.039 / 0.041 | 0.029 / 0.032 / 0.033 |
| 48 | 0.066 / 0.075 / 0.087 | 0.058 / 0.074 / 0.108 | 0.039 / 0.044 / 0.066 |

(three sweeps, each cell one run per sweep.) GB10's README row said the same from the
other direction long before this item: `RONTOLISP_THREADS=10` beat the 20 that box
defaulted to.

The default became `min(cpus, max(2, cpus / 2))` -- half, never below two on a box that
has two, never above the processor count. Re-measured at it: 9.81 / 9.00 tok/s on
Qwen3-0.6B and 8.24 on Qwen3.5-0.8B (8.33 at 64, a wash on that model).

Half is not a claim about SMT. It is what two boxes measured, and the reason is the same
on both: a GEMV is bandwidth-bound well before the last core, so the second half of a
machine's threads adds no throughput while each of them adds a thread the call must wait
for.

## 3. Rejected by measurement: the caller helping a straggler

The item's third candidate -- "work-stealing that lets the caller finish a preempted
worker's leaf instead of waiting for it -- the real fix, and the one with the most
surface". Two shapes were built and benched against the shipped one. Both LOSE on the
healthy path, and neither is in the tree.

**(a) Per-leaf done flags and leaf re-execution.** The row countdown becomes an
`AtomicIntegerArray` of one flag per leaf, and the caller re-runs any leaf still
outstanding after a budget. It is legal: a leaf is a deterministic write of rows no leaf
reads, so two threads running it write the same bits, and even a torn `double` is torn
between two identical values. Measured at 64 threads on an idle box, 0.041 -> 0.061
ms/call -- and the same 0.061 whether the budget was a flat 20 us or the call's own serial
cost (`rows * workPerRow / 4` ns, ~262 us at this shape, which never fires). **The cost is
the per-leaf flag traffic, not the rescue**: 64 cores writing 128 flags across 8 cache
lines, plus a caller that reads all of them. Two counting variants were tried (CAS-arbitrated
flags plus a shared `remaining`, and flags alone scanned by the caller); both landed on the
same 0.061, which is what identifies the flags rather than the atomics as the cost.

Keeping the old single countdown for the fast path does not rescue the idea: exactly-once
accounting needs the same per-leaf write either way, and the caller cannot simply return
early once it has re-run the outstanding leaves -- a stale worker still inside the
previous call's leaf would land its write in the NEXT call's `vec:matvec-into`
destination.

One more measurement worth keeping: with a budget short enough to fire (20 us) and a
"once rescuing, finish the call yourself" rule, the 16-thread / 48-busy cell went to 0.310
ms/call against 0.044 -- the serial kernel. When a box is UNIFORMLY oversubscribed every
thread is slow but they still add up, and the caller doing the work alone is strictly
worse. A rescue would have to distinguish "one straggler" from "everyone slow", which is
more machinery again.

**(b) `Thread.yield()` every 64 spins in the caller's wait.** The theory: the caller
occupies a CPU the descheduled worker needs, and the workers' own idle spin already yields
on exactly this rule. No consistent gain, and one cell at 0.220 ms/call against 0.066 --
once the caller yields on a full box it waits its turn among everything else on it.

The bench is checked in. Re-run it before trying a third shape.
