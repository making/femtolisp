# 749. `append` recurses once per element, so a long list is a StackOverflowError

Difficulty: Medium

Filed 2026-09-08 from orchestrator A's certification run at `8b3adb1e8` (`.todo/670`),
where it is the mechanism under `.todo/748` -- 748 is the corpus case that reaches it, this
is why reaching it crashes.

**`(append a b)` allocates its result by recursing once per element of `a`, on the
interpreter and on all three compile paths.** A ten-thousand-element first argument is not
a slow `append`, it is a `StackOverflowError`, and the frame is the only thing in the
stack:

```
java.lang.StackOverflowError
        at am.ik.rontolisp.eval.Environment.appendTwo(Environment.java:6984)   x1024
```

The four spellings, all the same shape (`list` is walked, `tail` is shared -- which is the
CL contract, so only the WALK is at issue):

- `eval/Environment.appendTwo` -- `new LispCons(cons.car(), appendTwo(cons.cdr(), tail))`.
- `codegen/jvm/JvmRuntimeBuilder` `_append` -- `new Object[]{a[0], _append(a[1], b)}`
  (JvmRuntimeBuilder.java:1545).
- `codegen/wasm/WasmRuntimeBuilder` `_append` -- the same body over `(ref null eq)`.
- Every caller that right-folds onto them: `JvmAppendCompiler` / `WasmAppendCompiler`
  (N-1 calls for N arguments, each recursing over its own argument) and
  `Jvm`/`WasmMapcanCompiler`, whose accumulator grows, so `mapcan` over a long list is
  quadratic in allocation AND linear in stack depth.

**The fix is one shape, applied four times: build the copy iteratively.** Walk `list`
forward, cons as you go, and patch the last cdr to `tail` -- the interpreter can do it with
a mutable `LispCons` tail pointer, and both emitters already emit loops elsewhere. Nothing
about the RESULT changes: the copy is fresh, the tail is shared, a dotted or improper
`list` argument still signals from the same place.

## Why it did not show up before

Nothing in the suite appended a list long enough. `.todo/748`'s corpus case does -- but
only on a box whose project tree has grown past the threshold, which is why this surfaced
as a box-dependent red rather than as a test. **Depth-bounded recursion in a runtime helper
is invisible to every test that stays under the bound**, and the bound is thousands, so
"it has never failed" is not evidence (rule 6 of `.todo/670`).

## Done when

- `append` over a 100k-element first argument returns on the interpreter, the JVM backend
  and both WASM backends, and `mapcan` over a 100k-element list likewise.
- A `ci-spec.yaml` case pins a length well past any plausible stack (the four backends
  agree on the result, which they already do -- what is new is that they answer at all).
- The sibling helpers are checked for the same shape while the fix is being written:
  anything in `Environment` / the two `RuntimeBuilder`s that recurses per ELEMENT rather
  than per NESTING LEVEL is the same defect (`nconc`, `revappend`, `copy-list`,
  `mapcar`'s accumulator). Report the census with its total and its class count, not a
  list of names.

**A per-element recursion is a length limit nobody wrote down.** That is the finding,
whatever the fix costs.
