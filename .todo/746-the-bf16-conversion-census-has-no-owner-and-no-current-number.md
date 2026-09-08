# 746. The hand-written bf16 conversion census has no owner and no current number

Difficulty: Low

`.todo/670`'s findings list carries: "**Seven sites hand-write the bf16 conversion
arithmetic** and only `am.ik.rontolisp.BFloat16` is the authority. Census:
`.todo/487`'s remainder." **`.todo/487` is closed**, so the census is a finding
with no owner -- rule 1 again, and the sibling of `.todo/745`.

Worse, the NUMBER is not usable as it stands. Rule 2: a count an item wrote down
is not a completion test, and this one cannot even start an audit, because it does
not say which sites it counted. A crude grep for a file that touches both bf16 and
16-bit shift arithmetic answers **twelve**:

```
BFloat16.java  LispBFloat16Array.java
codegen/jvm/{JvmBFloat16Compiler,JvmFloat16RuntimeBuilder,JvmFloatArrayRuntimeBuilder,
             JvmPackedFloatWidth,JvmSimdVectorTemplate}.java
codegen/wasm/{NoGcWasmCompiler,WasmBFloat16Compiler,WasmFloat16Compiler}.java
eval/{FloatBitsWidening,VecSimdKernels}.java
```

Twelve is not the answer either. **A backend has to EMIT the arithmetic** -- a
compiled program cannot call `am.ik.rontolisp.BFloat16`, because `BFloat16` does
not travel with the output (`.kb/bfloat16.md`). So an emitter spelling the
conversion is doing the only thing it can, and is not a duplicate in the sense the
finding meant. What the finding is about is the sites that COULD have called the
authority and did not, plus the emitters that spell it differently from each other
where they need not.

So the work is the counting, not a refactor.

## Do

1. Sort every site into three classes and say which each is:
   - **the authority** (`am.ik.rontolisp.BFloat16`),
   - **a necessary emission** -- a backend writing instructions into an artefact
     the authority cannot reach; these are duplicates by construction and the
     question about them is whether they agree, not whether they exist,
   - **an avoidable copy** -- host-side Java that could have called the authority.
2. For the necessary emissions, check they agree with the authority in the NARROW
   direction and on NaN. That is where every break has happened
   (2026-09-03, in a copy), and it is what `JvmBFloat16ArrayTest`'s exhaustive
   pattern sweep exists for -- name which emitters have such a sweep and which do
   not.
3. Fold only the avoidable copies. If there are none, **that is the result**: write
   the classification and the current count into `.kb/bfloat16.md`, dated, and say
   the finding is discharged.
4. Replace `.todo/670`'s findings line with a pointer at wherever the answer landed,
   per rule 9 -- the umbrella points, the child owns.

## Do not

- Do not "unify" a backend emitter into a call it cannot make. The reason the
  duplication exists is the `runtime`-imports-nothing rule and its wasm equivalent,
  and both are load-bearing.
- Do not re-derive the count from the number seven. Start from the grep.

## Done when

- Every site is classified, the emitters' agreement in the narrow / NaN direction is
  stated per site, and `.kb/bfloat16.md` carries the dated result.
- `./mvnw -Dtest=PathCitationTest test` is green and `.todo/670`'s line points at it.
