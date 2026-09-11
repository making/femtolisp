# A computed `:test` / `:key` designator is evaluated once per ELEMENT

Difficulty: Medium

Every sequence scan inlines the `:test`/`:test-not`/`:key` designator FORM into its loop
body (`LispMacroExpander.keyedForm` / `testMatchForm`, and the same shape in
`buildPositionScan`). For a literal `#'name` / `'name` / `(lambda ...)` that is exactly
right -- the compilers' function-designator normalization sees the literal and emits a
direct call. For a COMPUTED form it is wrong twice over: the form is evaluated once per
element instead of once, and it is evaluated inside the loop rather than in the
argument-evaluation order CLHS 3.1.2.1.2.3 requires (left to right, once each).

ANSI names it directly: the `*.ORDER.1` / `.ORDER.2` tests of `count`, `substitute`,
`substitute-if`, `substitute-if-not`, `nsubstitute`, `nsubstitute-if`,
`nsubstitute-if-not`, `position`, `find`, ... Each passes a `(progn (setf cN (incf i))
#'eql)` for every keyword and checks that `i` ended at the number of arguments and that
each `cN` records its own position. Measured 2026-09-11 (`.kb/sequence-bounding-keywords.md`):
`count.order.1` wants `1 7 1 2 3 4 5 6 7` and gets `1 11 5 1 2 3 11 4 10` -- 11 evaluations
of 7 forms, in the wrong order. ~14 tests in the `sequences` chapter alone, plus whatever
the other chapters' order tests cost.

## How to do it

1. In the expander, decide per designator FORM: a self-evaluating literal, a `(quote ...)`,
   a `(function ...)` and a `lambda` stay INLINED (no observable evaluation, and the
   normalization depends on it); anything else binds to a fresh variable once, before the
   loop, in the order the keywords appear in the CALL. `SeqScanScaffold.wrap`
   (`.kb/sequence-bounding-keywords.md`) already owns the "bind the keyword values once"
   chain for `:start`/`:end`/`:count`/`:from-end` -- it needs the designators alongside
   them, and the whole chain needs to bind in SOURCE order rather than a fixed one.
2. The item/predicate and the sequence bind before all of them (they already do).
3. Same treatment in `buildPositionScan` and the alist scans (`assoc`/`rassoc`/`member`),
   which share `keyedForm`/`testMatchForm`.
4. The interpreter's runtime twins evaluate arguments before the call, so they are already
   correct; the pin is a ci-spec case with a counting designator per keyword.

Found while closing `.todo/736` (2026-09-11).
