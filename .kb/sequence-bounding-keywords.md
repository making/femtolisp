# `:start` / `:end` / `:count` / `:from-end` across the count/remove/substitute family

**Invariant: the fifteen operators of the `count` / `remove` / `substitute` family take CLHS
17.2.1's bounding keywords through ONE scan shape per surface -- one expansion
(`LispMacroExpander.SeqScanScaffold`) for the call position and every backend, one runtime
(`LispEvaluator.sequenceScanValues`) for first-class use in the interpreter, and one
keyword-forwarding wrapper (`BuiltinFunctionWrappers.sequenceScanFamily`) for first-class use
on the compile paths. A call spelling NONE of them expands to exactly the loop it always did.**

`remove-duplicates`/`delete-duplicates` take the same keywords with a DIFFERENT meaning for
the window and are the section at the end of this file, not part of the fifteen.

The operators: `count`, `count-if`, `count-if-not`; `remove`, `remove-if`, `remove-if-not`;
`delete`, `delete-if`, `delete-if-not`; `substitute`, `substitute-if`, `substitute-if-not`;
`nsubstitute`, `nsubstitute-if`, `nsubstitute-if-not`. The `count` three have no `:count`.

## `:from-end` is a reversed WALK, not a tie-break for `:count`

**Measured 2026-09-11 against the ANSI suite, overturning the premise `.todo/736` and
`count-if-not`'s prelude comment both carried ("`:from-end` changes nothing for a count and
can be accepted and ignored"):** it reverses the order the elements are VISITED in, so the
`:test` and `:key` designators are called in that order. ANSI pins it with side-effecting
designators and no `:count` in sight -- `count-list.9` (a `:key` that counts its calls, 3
forward vs 4 reversed), `substitute-list.21`/`.23`, `substitute-bit-vector.24`/`.25`. An
implementation that scans forward and merely picks the last matches answers those wrong.

So every scan here is served by REVERSING the walked list and running the same forward loop:

- the element order, the designator call order and the order a `:count` budget is spent all
  follow from the walk, and the loop body never learns which direction it runs in;
- the accumulator a reversed walk builds is already in the answer's order, so the closing
  `nreverse` becomes conditional (`(if from acc (nreverse acc))`) instead of a second loop;
- `:start`/`:end` are mapped into the walk's own coordinates: `[len-end, len-start)` reversed.

## Piecewise emission (the size rule)

`SeqScanScaffold` emits each piece only when its keyword was spelled: the element index and
its `lo`/`hi` bounds for `:start`/`:end`, the count budget for `:count`, the `reverse`/`length`
pair for `:from-end`. With none of them the expansion is byte-identical to the pre-`.todo/736`
one, so no existing call site pays. `SeqScanBounds.absent()` is the gate; `wrap` binds the
keyword VALUES once, outside the loop (they used to be re-evaluated per element where the
expansions inlined them -- `:test`/`:key` still are, deliberately, so a literal `#'name`
resolves through the compilers' function-designator normalization).

- `:start`/`:end` bound the walk BY INDEX, never by handing the scan a `(subseq ...)`: the
  excluded elements must not reach a designator, and `count` used to build the subsequence
  whole before looking at the first element.
- The guard (`in range` and `budget left`) is evaluated BEFORE the match form, so a designator
  is never called outside `:start`/`:end` or past an exhausted `:count`.
- A negative `:count` acts as zero, a nil one as no limit (CLHS 17.2.1); a nil `:start` is 0
  and a nil `:end` is the end. A nil `:key`/`:test` is the ABSENT designator, not a function
  to call -- `keyedForm`/`testSpec` read a literal nil that way (ANSI spells
  `(remove 'a x :key nil)`), and the runtime twin reads a nil VALUE the same way.

## The destructive spellings

- `nsubstitute`/`-if`/`-if-not` keep rewriting the argument's own cons cells. Under
  `:from-end` the scaffold's `cells` mode walks a list OF THOSE CELLS, built in either
  direction by the same trick -- reversing the LIST would hand it fresh cells whose `rplaca`
  nobody can see. The cell list is built only when `:from-end` is spelled at all; every other
  bounded destructive scan walks the argument itself and allocates nothing.
- `delete`/`-if`/`-if-not` with ANY bounding keyword answer a FRESH sequence (they route to
  the matching `remove` scan): CLHS lets a destructive operator do that, the caller must use
  the RESULT either way, and the `rplacd` splice cannot serve `:from-end`, whose cells would
  have to be visited backwards through a singly linked spine. Unbounded, the splice is
  unchanged. The interpreter's runtime twin splices under exactly the same condition, so the
  two paths agree on when the argument is mutated.

## The three surfaces, and what keeps them honest

- **Expansion**: `LispMacroExpander` -- `expandFilter` (remove family), `substituteScan`,
  `nsubstituteScan`, `countScan`, all over `SeqScanScaffold`. Reached by the interpreter's
  call position (`evalBuiltinMacro`) and by both backends' operator cases.
- **Interpreter runtime**: `LispEvaluator.sequenceScanValues` -- one method for all fifteen,
  parameterized by `SeqScanMode` (item / predicate / negated predicate) and `SeqScanAction`
  (count / remove / substitute) plus a destructive flag. Every registration in the family goes
  through it, so `(funcall #'remove ... :count 1)` and `(remove ... :count 1)` agree. These
  registrations live in `LispEvaluator`, not `Environment` (the designators need `apply`),
  which is why the five names moved into `ShadowedBuiltins.EXPANSION_LOWERED`.
- **Compiled first class**: `BuiltinFunctionWrappers.sequenceScanFamily`, on the
  `positionFamily` model -- the wrapper re-extracts the runtime keywords with `getf` and calls
  the CALL-POSITION form, so the expansion stays the only implementation. `:test-not` is
  normalized to a complemented `:test`.
- `count-if-not` is a prelude defun and is now just `count-if` over the complemented
  predicate, forwarding the whole keyword set (`:from-end` included -- see above).

## What it moved, and what it did not (ANSI `sequences`, interpreter, 2026-09-11)

`ansi-test/measure.sh sequences`: **2,204 / 3,287 -> 2,861 / 3,287 pass** (67.1% -> 87.0%),
errors 934 -> 265, and a name-by-name diff of the FAIL/ERROR sets shows 657 tests fixed and
**zero tests that passed before failing after**. The census of `X expects keyword arguments`
rows was an upper bound of 578 plus 67 `COUNT-IF expects 2 arguments` rows; the realized gain
landed between them, because a test can fail for a second reason once the first is gone.

Two families of newly-REACHED failures this exposed, both out of scope here:

- **`*.ORDER.1/2`** (7 operators): CL evaluates the keyword VALUE forms left to right, once
  each. The scaffold binds `:start`/`:end`/`:count`/`:from-end` once, but in a FIXED order, and
  `:test`/`:key` were still INLINED into the loop, so a computed designator ran per element.
  Fixed 2026-09-11 by `LispMacroExpander.KeywordTail`, which hoists every non-literal keyword
  value of the call -- these four included -- into one source-ordered `let` chain outside the
  scaffold, leaving `wrap`'s own fixed-order bindings to copy variables:
  `.kb/sequence-designator-evaluation.md`.
- **`NSUBSTITUTE-*-VECTOR.3/.32/.33`**: a destructive substitute over a VECTOR answers a fresh
  sequence instead of writing through (`.todo/623`'s latitude). ANSI expects the argument itself
  to change (`.todo/773`).

While closing the gap, `Environment.seqAsList` was found to have no arm for a rank-1
`LispFloatArray` -- see `.kb/seq-coerce-runtime.md`.

## `remove-duplicates` / `delete-duplicates`: the window bounds what is CONSIDERED

The two spellings take the same 17.2.1 set minus `:count` (`:from-end`, `:test`,
`:test-not`, `:start`, `:end`, `:key`) but they are NOT the scan above, and
`SeqScanScaffold` serves them only in part:

- `:start`/`:end` bound which elements are **compared**, not which ones reach the answer.
  An element outside the window is kept VERBATIM and never handed to a designator, so the
  guard's else arm ACCUMULATES where the fifteen's skips.
- `:from-end` picks which occurrence of a duplicate set survives (the first instead of the
  last), so it decides which SIDE of the element the duplicate is looked for on -- not the
  order the walk runs in.

One forward loop serves all of it. The duplicate is looked for with the position family's
own bounded scan -- `[i+1, end)` keeping the last, `[start, i)` keeping the first -- which
is both how CLHS defines the operator and how ANSI's own reference implementation
(`auxiliary/remove-duplicates-aux.lsp`) spells it. That is what lets a **computed**
`:from-end` be a branch over two INDEX BOUNDS inside one loop rather than over two loops:
it used to be rejected outright (`IllegalArgumentException`, "expects a literal t or nil"),
which is exactly what ANSI's `remove-duplicates.order.1/2` -- whose `:from-end` counts its
own evaluation -- failed on.

The piecewise rule holds here too, and decides between TWO renderings: a call that spells
no bound and a LITERAL direction keeps the `member`-over-the-tail (or over the accumulated
answer) loop it always expanded to, index-free and byte-identical; only a bound or a
computed direction pays for the index, the guard and the inner bounded scan. The scaffold's
`forceIndex` exists for the second case, where the BODY needs the element index though no
guard does.

First-class use is still 1-argument only (`(apply #'remove-duplicates seq :test ...)`
signals), unlike the fifteen -- `.todo/778`.

**Measured 2026-09-11** (`ansi-test/measure.sh sequences cons`, suite `ca06bd9`,
interpreter): sequences 2,891 -> 2,895 / 3,287 (88.0% -> 88.1%), cons unchanged at
1,082 / 1,879. A name-by-name diff of the FAIL/ERROR sets: **4 tests fixed**
(`remove-duplicates.order.1/2`, `delete-duplicates.order.1/2`), **zero that passed before
failing after**. The census of `X expects keyword arguments` rows had no plain (non-order)
duplicates test behind it -- what is left in that file is `*.error.10` (an unbound
`*mini-universe*`), `remove-duplicates.fold.4` (constant folding) and the two `random-*`
tests, which stop at `make-sequence` with a computed result type before any of this.

## Pinning tests

- `LispMacroExpanderTest.aBoundedSequenceScanEmitsOnlyTheScaffoldingItsKeywordsAskFor` (the
  piecewise/size invariant, and that `:start`/`:end` is an index bound rather than a `subseq`).
- `LispMacroExpanderTest.aBoundedRemoveDuplicatesLooksForTheDuplicateInsideTheWindow` and
  `LispEvaluatorTest.evalRemoveDuplicatesTakesTheBoundingKeywords` (the two renderings, the
  window's verbatim else arm, the computed direction), with
  `JvmLispCompilerTest.compileAndRunRemoveDuplicatesBoundingKeywords`,
  `WasmLispCompilerIntegrationTest.removeDuplicatesBoundingKeywords` and ci-spec
  `remove-duplicates-bounding-keywords` across the four backends.
- `LispEvaluatorTest.evalSequenceScansTakeTheBoundingKeywords` (behavior, call position AND
  first-class, including the counting `:key` that only a reversed walk answers).
- `JvmLispCompilerTest.compileAndRunSequenceBoundingKeywords`,
  `WasmLispCompilerIntegrationTest.sequenceBoundingKeywords`, ci-spec
  `sequence-bounding-keywords` (all four backends).
- The rejection text is part of the contract:
  `REMOVE expects keyword arguments :TEST/:TEST-NOT/:KEY/:START/:END/:COUNT/:FROM-END, got: X`
  (`.kb/error-handling.md`, "Argument-shape errors"), pinned in ci-spec and in the
  interpreter/JVM/WASM argument-shape tests, and shown in `doc/**/macros/handler-case.md`.
