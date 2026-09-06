# 719. `subseq`/`%array-alike` of a packed vector loses the width on the JVM and WASM backends

Difficulty: High

Found 2026-09-06 finishing `.todo/698` (interpreter fix for the same defect). Measured
with `probe.lisp`:

```lisp
(let ((b (make-array 3 :element-type '(unsigned-byte 8) :initial-contents '(65 66 67))))
  (print (type-of (subseq b 0 2))))                    ; (SIMPLE-ARRAY (UNSIGNED-BYTE 8) (2)) -- correct, every backend
(let ((s (make-array 3 :element-type 'single-float :initial-element 1.5)))
  (print (type-of (subseq s 0 2))))                     ; interpreter (after 698): (SIMPLE-ARRAY SINGLE-FLOAT (2))
                                                          ; JVM / wasm: (SIMPLE-VECTOR 2)  -- WRONG
(let ((v (make-array 4 :element-type '(unsigned-byte 8) :fill-pointer 0 :adjustable t)))
  (vector-push-extend 65 v) (vector-push-extend 66 v)
  (print (type-of (subseq v 0 2))))                      ; interpreter (after 698): (SIMPLE-ARRAY (UNSIGNED-BYTE 8) (2))
                                                          ; JVM / wasm: (SIMPLE-VECTOR 2)  -- WRONG
```

So on the JVM and WASM backends, `subseq` of ANY packed vector -- simple or
adjustable/fill-pointer, `(unsigned-byte N)` or a packed float width -- degrades to a
general boxed vector whenever the general-array copy path is taken, i.e. whenever the
source is not itself the runtime `long[]` (packed-int) representation. `ci-spec.yaml`'s
`subseq-of-an-adjustable-packed-vector` case pins this CURRENT (wrong) JVM/wasm output
via `expectedByBackend` so a fix here shows up as a spec diff, not a silent pass.

## Root cause

`subseq` of a general array lowers (`LispMacroExpander.expandSubseqCompat`) to
`(%array-alike seq n)` plus an `aref`/`%aset` copy loop. `%array-alike`'s job is to
allocate a fresh array with the SAME representation as `seq`:

- JVM: `JvmArrayCompiler.compileArrayAlike` -> the single helper `_ivAlike`
  (`JvmIntArrayRuntimeBuilder.buildAlike`), whose bytecode tests ONLY
  `instanceof long[]` (the packed-int marker) and falls to a general `_arrayMake`
  otherwise -- a packed float array (`double[]`/`float[]`/`short[]` with a
  `JvmPackedFloatWidth` header) is not recognized at all. Worse: `_ivAlike` itself is
  only emitted when `ctx.usesIntArray`; a float-only program takes
  `LispMacroExpander.expandArrayAlikeGeneral` instead (`(progn seq (make-array n))`),
  which never preserves anything.
- WASM: `WasmArrayCompiler.compileArrayAlike` tests `TYPE_I8ARR`/`TYPE_I16ARR`/
  `TYPE_I32ARR` (packed int) only; a packed float array (`TYPE_FARRAY`, or a
  `TYPE_VBLOCK` payload under `--simd`) falls to the same "general" arm.

Neither backend's `%array-alike` ever consults `LispArray.elementTypeCode()` either, so
even a NON-float adjustable packed vector (the `.todo/698` shape) loses its width here on
these two backends, independent of the float gap above.

## Do

1. Give `_ivAlike` (or a sibling `_fvAlike`) a packed-float branch on the JVM: test each
   `JvmPackedFloatWidth` backing (`double[]`/`float[]`/`short[]`) the way `buildAlike`
   tests `long[]`, and allocate a zero-filled array of that width with a fresh rank-1
   header (`[1, n, 0...]`, bfloat16's `[1, hi(n), lo(n), 0...]` -- `JvmPackedFloatWidth`
   is the one place that already knows the layout, use it rather than re-deriving the
   offsets). Broaden the emission gate so the combined helper exists whenever
   `usesIntArray || usesFloatArray`, and retire (or no-op) `expandArrayAlikeGeneral`'s
   silent degrade once a float-only program has a real compiled path.
2. Same shape on WASM: extend `WasmArrayCompiler.compileArrayAlike`'s `ref.test` chain
   with `TYPE_FARRAY` (and the `--simd` `TYPE_VBLOCK` payload case), allocating the
   matching packed array instead of falling to the general arm.
3. Neither backend's `%array-alike` currently reads `LispArray.elementTypeCode()` for the
   case where `seq` is the GENERAL boxed representation but remembers a packed width (a
   fill-pointer / adjustable packed vector, `.kb/adjustable-arrays.md`) -- only the
   interpreter does, after `.todo/698`. Decide whether the compilers can even OBSERVE
   that field at compile time (it is a runtime fact on a general array) or whether the
   fix has to read it via a new `_arrayElementTypeCode`-style runtime accessor; either
   way both backends need it, not just the packed-float gap above.
4. Update `ci-spec.yaml`'s `subseq-of-an-adjustable-packed-vector` case: once fixed,
   drop the `expectedByBackend` overrides so the single `expected:` block covers all
   four backends (matching `packed-integer-vectors`, which already pins the simple
   `(unsigned-byte 8)` case identically everywhere).
5. `JvmLispCompilerTest` / `WasmLispCompilerIntegrationTest` cases for `%array-alike`
   over a packed float array and over an adjustable packed vector of each width.
