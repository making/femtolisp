# 747. The element-wise `bfloat16` `vec:` kernels

Difficulty: High

`--simd` fuses three members at `bfloat16` -- `vec:sum`, `vec:dot` with a bf16 first
operand, `vec:matvec` over a bf16 matrix -- and DECLINES every element-wise call at the
width to the scalar `vec.lisp` defun (`.kb/bfloat16.md`, "The packed array"). The
declining was justified by a prediction that **the measurement overturned on 2026-09-08**
(`.todo/696`; numbers, harness and reasoning in
`.todo/artefacts/696-the-narrow-width-element-wise-kernels/README.md`).

## What is already settled, and must not be re-measured

- **The narrowing vectorizes.** A branch-free lane form of `floatToBf16` is 1.4-3.2x the
  scalar loop at every size on both JITs, and agrees with the scalar on all 2^32 f32
  patterns. The form is written out in that README and in
  `src/test/java/am/ik/rontolisp/eval/Bf16NarrowBench.java`.
- **The composite is 2.3-2.7x** the scalar route (widen both operands, add in f32, narrow
  on store), and above 16 M elements it beats the f32 element-wise kernel outright.
- **"Compute in f32" is exactly the defun's answer**, not merely close: swept over all
  65536x65536 operand pairs for `+ - * /`, 0 mismatches against the defun's f64 route.
  So the shape `.todo/488` wrote down -- widen, compute in f32, narrow on store, never
  keep an intermediate at the narrow width -- is the correct one as well as the fast one.
- **The guard and the bridge are width-agnostic; the PAIRING is a plan decision**
  (`.kb/bfloat16.md`, the bullet under `--simd` FUSES). Widening `BF16_OPERAND` from one
  position to a SET of positions, plus one `instanceof` chain in the bridge entry, is the
  whole mechanism. The bridge stays TOTAL either way.

## What this item has to decide, and then do

**1. The admitted pairings, and the result width.** This is the design question, and it is
the same KIND of question as the GEMV's "narrow weights against f32 activations" -- a plan
decision, not a discovery. The candidates, in rising order of blast radius:

- bf16 op bf16 -> bf16 (the obvious one: a program that chose the width stays in it)
- bf16 op f32 -> ? (today the defun answers, and the defun's own width rule is what the
  kernel must reproduce -- read `vec::%make-like`, do not guess)
- a bf16 `-into` destination with f32 sources

Whatever is chosen, **every combination the guard admits must have a kernel**, and every
combination it does not must reach the defun with the SAME answer it gives today. The
existing trap applies unchanged: every width test is POSITIVE, or the next representation
falls through to a cast.

**2. Which members.** `vec:add`/`sub`/`mul`/`div`/`scale`, the seventeen unary ufuncs, the
four comparison selects, and all their `-into` siblings is ~40 kernels MIRRORED across
`eval/VecSimdKernels` and `codegen/jvm/JvmSimdVectorTemplate`. That is the cost the
original prediction was weighing, and it did not go away when the speed question flipped.
The transcendental ufuncs have no lane form at all today even at f32 (they are per-element
loops), so they are not candidates; start from the members that already have an f32 lane
loop, and be willing to ship a SUBSET. A member that a `#bf16` program never calls is 2
new methods, 2 new guard positions and 2 new tests for nothing.

**3. The mirror.** The two kernel files are read and tested as one operation-for-operation
pair (`VecSimdBf16KernelsTest`'s class javadoc). Whatever lands, lands in both, with the
narrowing lane form written the same way in both -- and note that `floatToBf16`'s NaN arm
already diverged silently once between those two copies for five days (`.todo/746`).

**4. WASM does not participate**, and that is not a gap: no WASM backend has the width at
all. The cross-backend identity contract is unaffected -- the element-wise kernels are
bit-exact at any lane count, which is why they run at `SPECIES_PREFERRED` rather than the
reductions' pinned four.

**5. Tests.** Per side, the exhaustive shapes the width already uses: the lane narrow
against `BFloat16.bits` over all 65536 bf16-widened patterns (the existing
`theNarrowingAgreesWithTheAuthorityOnEveryBf16WidenedPattern` pins the scalar one), each
new kernel against the defun, the declined combinations still equal to the defun, and a
`ci-spec.yaml` case if a member deserves cross-backend coverage. `eval/VecSimdTest` and
`JvmBFloat16ArrayTest`'s `--simd` section are where the twins live.

## What is NOT in scope

The NARROW x NARROW pairing for the REDUCTIONS (`dotBf16Bf16` and friends). That is
answered as an extension in `.kb/bfloat16.md` and wants a caller before it wants code.
