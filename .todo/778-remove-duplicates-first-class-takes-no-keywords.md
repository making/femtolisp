# `remove-duplicates` / `delete-duplicates` take no keywords as first-class values

Difficulty: Medium

In call position both spellings now take the whole CLHS 17.2.1 set
(`:test`/`:test-not`/`:key`/`:start`/`:end`/`:from-end`, `.kb/sequence-bounding-keywords.md`).
As a FUNCTION VALUE they take exactly one argument on every surface:

```lisp
(apply #'remove-duplicates (list 1 2 1) '(:test #'eql))
; REMOVE-DUPLICATES expects 1 argument, got 3
```

- interpreter: `Environment.createGlobal` registers both names as a 1-argument
  `LispFunction` that compares with `eql` and reads no keyword at all.
- compile paths: `BuiltinFunctionWrappers` lists them as `unary(...)`.

The fifteen operators of the count/remove/substitute family fixed the same gap with two
pieces -- `LispEvaluator.sequenceScanValues` (the interpreter's runtime twin of the
expansion) and `BuiltinFunctionWrappers.sequenceScanFamily` (a wrapper that re-extracts the
runtime keywords with `getf` and calls the CALL-POSITION form, so the expansion stays the
only implementation).

## How to do it

1. The compiled half looks like one line: `sequenceScanFamily(LispNames.REMOVE_DUPLICATES,
   true, false, 0)` -- `operands` 0 gives `(seq &rest kw)`. Check what it passes: a
   `:start` defaulted to 0 and a computed `:from-end` both land in the expansion's GENERAL
   rendering, which is correct but never the fast path. Confirm the `:test-not` ->
   complemented `:test` normalization it emits (it uses `complement`, whose one-argument
   lambda is `.todo/774`'s bug -- the rest of the family already lives with it).
2. The interpreter half needs a Java twin that walks the sequence with the designators
   through `apply`, which is why the family's registrations live in `LispEvaluator` rather
   than `Environment` (and why the names then join `ShadowedBuiltins.EXPANSION_LOWERED`).
   Do NOT ship only the compiled half: the two surfaces must agree.
3. ANSI's `random-remove-duplicates` / `random-delete-duplicates` are the tests behind this
   (`(apply #'remove-duplicates seq arg-list)` against the suite's own reference
   implementation, 1000 random parameter sets each) -- but they stop EARLIER, on
   `make-sequence` with a computed result type, so closing this alone moves no ANSI number
   until that is fixed too. Measure before claiming a gain.

Found while closing `.todo/775` (2026-09-11).
