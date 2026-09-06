# An FFM downcall costs 2.1 us inside a native image -- 230x the same call on the JVM

Filed 2026-09-06 by `.todo/476`'s close, off the five-arm probe that item was refused on
(`.todo/artefacts/476-ffm-downcalls-through-a-non-constant-method-handle/`, section 1).
Difficulty: High -- the mechanism is inside SubstrateVM, not inside this repo, and the
first honest step is to find out what the 2 us IS before deciding whether we can spend it.

`.kb/gpu.md` has said since `.todo/123` that "the per-call cost of an FFM downcall inside
a native image is still unexplained; the generic `MethodHandle` invoker under every
downcall is the suspect." The cost is now MEASURED and the suspect is narrowed.

## The measurement

Two million calls of `cuDriverGetVersion(int*)` on GB10, GraalVM 25.0.4, steady state:

| how the handle is held | GraalVM JIT | C2 | **native image** |
| --- | --- | --- | --- |
| `static final MethodHandle` | 8.9-9.1 ns | 9.44 ns | **2082 ns** |
| `final` instance field, constant receiver | 8.3-8.9 ns | 10.13 ns | **2083 ns** |
| `final` instance field, mutable receiver | 10.0 ns | 10.2-11.2 ns | **2083 ns** |
| `static final`, `critical(true)` | 5.3-6.5 ns | 5.43 ns | **2118 ns** |
| no downcall (same loop, same interface) | 3.6 ns | 2.3 ns | 32.8 ns |

The control row is what makes the other four a downcall finding rather than a "the binary
is slow" one: the same loop with the call removed is 32.8 ns, 9x the JVM's 3.6, while the
downcall rows are 230x.

Two things it rules out immediately:

- **Handle CONSTANCY is not it.** All four arms agree to 2%. AOT code is compiled before
  the handle exists (the lookup runs at image RUN time), so no arm can be a constant --
  which is also why `.todo/476` could not have fixed this and was refused.
- **The thread transition is not it, and `critical(true)` is a PESSIMISATION here.** On
  both JITs critical is the fastest arm (it skips the transition); in the image it is the
  slowest. Whatever the 2 us is, it is upstream of the transition and critical adds to it.

What is left, in the order worth trying: a `LambdaForm` interpreted rather than
intrinsified in AOT (SVM intrinsifies method handles it can see at BUILD time, and none of
these are), argument marshalling through a generic path, or a per-call check the JVM does
once at bind time.

## Why it matters here

Every FFM surface the native binary carries pays it, and three of them are per-call:

- `--gpu`. The book-shape step issues its copies, its `cuMemAllocAsync` and its
  `cuLaunchKernel` per member; the JVM class output's step is 0.69 s with the driver calls
  at 9 ns each, and at 2.1 us each the same call count costs 230x more of it.
- `--blas`. `LinalgBlasKernels.MIN_WORK` is 64 multiply-adds, and its comment says why:
  "the fixed cost of a critical downcall is ~30 ns". That constant was calibrated on the
  JVM. If the fixed cost is 2.1 us in the binary, the crossover it encodes is ~70x too low
  THERE, so the native binary intercepts products it should decline -- a silent
  slowdown of exactly the shape `.todo/649` was about.
- `objc:`. `.kb/objc.md` says the native binary serves a closed shape table through
  `invokeWithArguments`, which is the generic path in full.

## What to do

1. **Reproduce and attribute.** The probe is already standalone; run it under `perf` on the
   built image, and against a hand-written JNI-free control. `-H:+PrintMethodHandleGraphs`
   or the equivalent AOT dump says whether the LambdaForm survived as a graph.
2. **Try the one thing that could make it a constant**: a handle bound at BUILD time
   (`--initialize-at-build-time` over a holder). It probably fails -- a native pointer in
   the image heap -- but the failure is the answer to "can AOT ever fold one of these".
3. Whatever the outcome, **re-derive `MIN_WORK` for the native binary** or state in
   `.kb/linalg-blas.md` that the threshold is a JVM number. A wrong crossover is a
   regression the flag causes, which is worse than a slow call.
4. Report it upstream if it is SVM's: the numbers here are a clean, minimal reproducer.

## Acceptance

The 2.1 us is attributed to a named mechanism (not a guess -- `.todo/670` rule on profiles
naming the cost correctly and the cause as a guess applies), `.kb/gpu.md`'s "still
unexplained" sentence is replaced by what it is, and every per-call threshold calibrated
against the JVM's downcall floor either holds in the binary or is re-derived there.
