# The `-if` / `-if-not` spellings of `member` / `assoc` / `rassoc` take no `:key`

Difficulty: Low

`(member-if pred list :key #'car)` answers `MEMBER-IF expects 2 arguments, got 6`, and so
do `assoc-if`, `assoc-if-not`, `rassoc-if` and `rassoc-if-not`. CLHS gives all six a
`:key`. ANSI fails on it in `member-if.order.1/2`, `member-if-not.order.1/2`,
`assoc-if.order.2`, `assoc-if-not.order.1/2`, `rassoc-if.order.2`,
`rassoc-if-not.order.1/2` plus the plain `:key` tests around them.

Both surfaces need it, and they are different code:

- the EXPANSIONS `expandMemberIf` / `expandAssocIf` / `expandRassocIf` (and the `-if-not`
  flavors), which serve the compile paths -- they already have `keyedForm` and
  `keywordTailError` beside them, and the keyword tail must go through
  `LispMacroExpander.KeywordTail` so a computed designator binds once
  (`.kb/sequence-designator-evaluation.md`);
- the interpreter's RUNTIME functions `MEMBER_IF` / `ASSOC_IF` / `RASSOC_IF` in
  `LispEvaluator`, which is where the arity message above comes from. They are what the
  interpreter's call position uses (unlike `count`/`remove`, these are not
  `evalBuiltinMacro` cases), so fixing only the expansion moves nothing in the ANSI run.
  Read a nil `:key` VALUE as the absent designator, the way `member`/`assoc`/`rassoc` now
  do with `presentKeyword`.

Pin it in ci-spec so the four backends agree.

Found while closing `.todo/772` (2026-09-11).
