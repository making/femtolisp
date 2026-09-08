# Does the bfloat16 NARROWING vectorize? The measurement, 2026-09-08

The artefact of `../../696-narrow-width-element-wise-kernels-and-the-operand-pairing.md`,
part 1. That item -- and `.todo/488` before it, which filed it -- carried a PREDICTION as
the reason the element-wise `vec:` kernels decline a `bfloat16` operand:

> The decode is one shift and vectorizes; the NARROWING does not. `floatToBf16` is
> round-to-nearest-ties-to-even with a guarded NaN arm, which is why `narrowBf16Into` is a
> scalar loop in both kernel files while `widenBf16Into` is a lane loop. An element-wise
> kernel stores every element, so it would be a scalar store loop wearing a vector load --
> close to the defun, at the cost of ~40 new kernels mirrored across two files. Measure a
> narrowing lane form BEFORE writing any of them: if it does not vectorize, the answer is
> to leave the decline in place.

**It vectorizes. The premise is overturned, and the answer is the opposite of the one the
item expected to record.**

## The harness

| what | where |
| --- | --- |
| the probe and the timing | `src/test/java/am/ik/rontolisp/eval/Bf16NarrowBench.java` |
| the runner, both JITs, labelled | `bench.sh` |

```bash
./mvnw -o test-compile
.todo/artefacts/696-the-narrow-width-element-wise-kernels/bench.sh
```

Not a surefire test (the naming patterns skip a `*Bench`); `./mvnw test` never runs it.
Both JITs, for the reason `.todo/482` round 2 found and `.todo/488`'s harness states: the
same Vector API source ran at 1.51x under Graal and 0.20x under C2 because one method
overran C2's inlining budget and every vector was silently boxed. A shape that is fast
under one and boxed under the other is not done.

## The lane form

`VecSimdKernels.floatToBf16` operation for operation, with the branch replaced by a mask,
so every lane pays for both arms:

```java
IntVector bits = v.reinterpretAsInts();
IntVector hi = bits.lanewise(LSHR, 16);
IntVector rounded = bits.add(0x7fff).add(hi.and(1)).lanewise(LSHR, 16);
IntVector u = hi.and(0xffff);
IntVector nan = u.or(u.and(0x7f).sub(1).lanewise(LSHR, 31));
VectorMask<Integer> isNan = bits.and(0x7f800000).compare(EQ, 0x7f800000)
    .and(bits.and(0x007fffff).compare(NE, 0));
return (ShortVector) rounded.blend(nan, isNan).convertShape(I2S, SS, 0);
```

The narrowing store is one `I2S` shape conversion (8 int lanes -> 8 short lanes on this
box). `SPECIES_PREFERRED`, not the reductions' pinned `SPECIES_128`: the element-wise
kernels are bit-exact at any lane count, which is why the shipped f32 ones already run at
the preferred width.

## Correctness first

A faster function that is a DIFFERENT function is not a kernel, so the bench opens with an
exhaustive sweep and prints the count:

- **All 2^32 f32 patterns, lane narrow against the scalar `floatToBf16`: 0 mismatches**,
  under both JITs. NaN payloads, signalling NaNs, the ties, the overflow-to-infinity arm.

## Timing (2026-09-08, x64)

**Base commit `5b1213675`. Intel Xeon E5-2697A v4 (Broadwell-EP), AVX2, 64 threads,
Oracle GraalVM 25.0.4. Load average 0.46 before the run.** `ms` is per call; `vs scalar`
is against the scalar arm of the SAME group (widen against widen, narrow against narrow,
add against add) -- reading a narrow against a widen would be comparing two functions.
Best of five rounds after eight warm-ups, as `.todo/488`'s harness times.

### The narrow itself

| n | Graal scalar | Graal lanes | Graal | C2 scalar | C2 lanes | C2 |
| --- | --- | --- | --- | --- | --- | --- |
| 1024 | 0.001 ms | 0.000 | **3.50x** | 0.001 | 0.000 | **2.41x** |
| 65536 | 0.088 | 0.028 | **3.15x** | 0.060 | 0.030 | **1.99x** |
| 1048576 | 1.343 | 0.414 | **3.24x** | 0.972 | 0.459 | **2.12x** |
| 16777216 | 21.934 | 11.300 | **1.94x** | 16.215 | 11.321 | **1.43x** |

The scalar narrow runs at 0.74-1.09 Gelem/s at every size -- it is compute-bound, not
memory-bound, which is exactly why there is something for lanes to win. The lane form
reaches 1.5-2.5 Gelem/s and the ratio falls at 16 M elements only because 67 MB of source
plus 34 MB of destination is where memory takes over.

For scale, the direction that was never in doubt: the shipped `widenBf16Into` lane loop is
1.00-1.04x of a scalar widen under Graal at n >= 65536 and 1.43-1.55x under C2. **The
narrowing vectorizes BETTER than the widening does**, because the widening is already
memory-bound at one shift per element while the narrowing has real arithmetic to
parallelise.

### The composite: `add` of two bf16 vectors into a bf16 vector

The kernel an element-wise arm would actually be -- widen both operands, add in f32,
narrow on store -- against the wholly scalar loop, and against the shipped f32 `addIntoF`
over the same element count as a ceiling. All three bf16 arms printed identical arrays.

| n | scalar | lane load + scalar store | all-lane | all-lane vs scalar | f32 `addIntoF` |
| --- | --- | --- | --- | --- | --- |
| 1024 (Graal) | 0.002 ms | 0.002 (1.04x) | 0.001 | **2.73x** | 0.000 |
| 65536 (Graal) | 0.119 | 0.111 (1.07x) | 0.044 | **2.71x** | 0.029 |
| 1048576 (Graal) | 1.829 | 1.708 (1.07x) | 0.726 | **2.52x** | 0.449 |
| 16777216 (Graal) | 30.025 | 28.494 (1.05x) | 11.724 | **2.56x** | 18.463 |
| 1024 (C2) | 0.002 | 0.002 (0.90x) | 0.001 | **2.52x** | 0.000 |
| 65536 (C2) | 0.098 | 0.110 (0.89x) | 0.039 | **2.52x** | 0.029 |
| 1048576 (C2) | 1.574 | 1.670 (0.94x) | 0.677 | **2.32x** | 0.456 |
| 16777216 (C2) | 25.959 | 27.844 (0.93x) | 11.203 | **2.32x** | 18.678 |

Three things to read out of it.

- **The middle column is the shape the item predicted, and it is worthless exactly as
  predicted**: 0.89-1.07x of the plain scalar loop. A vector load in front of a scalar
  store loop buys nothing. The prediction about THAT shape was right; the inference that
  it was the only available shape was not.
- **The all-lane arm is 2.3-2.7x the scalar defun's arithmetic**, at every size, on both
  JITs. C2's inlining cliff did not appear.
- **At 16 M elements the bf16 all-lane arm beats the f32 kernel outright** (11.2-11.7 ms
  against 18.5-18.7) because it moves half the bytes. Below that the f32 kernel is ahead
  (0.44 against 0.73 at 1 M) -- the same cache-resident-versus-streaming crossover the
  fused GEMV has, and for the same reason.

## The other question the measurement had to answer

An element-wise kernel STORES at the narrow width, so what it must equal is not another
kernel but the `vec.lisp` DEFUN -- and the defun reads each element as a `double`,
computes in `double`, and narrows on `setf aref` through `BFloat16.bits(double)`. A kernel
that widens to f32 and computes in f32 rounds a third time in between. That is a
double-rounding hazard and it has nothing to do with speed; had it bitten, "widen, compute
in f32, narrow on store" would have been the wrong shape and the whole idea would need an
f64 lane form at half the lanes.

It does not bite, and the reason is structural: `BFloat16.bits(double)` itself falls
through to `bits((float) value)` for every non-NaN, so both routes end in the same
f32 -> bf16 step, and binary64 carries 53 >= 2 * 24 + 2 bits, the classical condition for a
binary64 intermediate to round harmlessly to binary32 for `+ - * /`. Theory is not a pin,
so the bench sweeps it:

- **All 65536 x 65536 operand pairs, `add` / `sub` / `mul` / `div`, f32 intermediate
  against the defun's f64: 0 mismatches each.** (`bench.sh` runs this once, before the
  two timing runs; it is JIT-independent and takes about 80 s.)

So the shape `.todo/488` wrote down -- widen, compute in f32, narrow on store, never keep
an intermediate at the narrow width -- is not just the fast one, it is the one that agrees
with the defun bit for bit.

## What this decides

Writing the element-wise kernels is worth it: 2.3-2.7x over the scalar route, bit-identical
to the defun, with the equivalence question already answered exhaustively for the four
arithmetic members. What it does NOT decide is the design of the arm -- which pairings the
guard admits, what width the result takes when the operands differ, and which of the ~40
members are worth mirroring across the two kernel files. That is a plan decision of the
same kind as the GEMV's "narrow weights against f32 activations", and it is filed as
`.todo/747`; `.kb/bfloat16.md` carries the summary.
