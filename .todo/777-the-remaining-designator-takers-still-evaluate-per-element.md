# The remaining designator-takers still evaluate a computed `:test` / `:key` per element

Difficulty: Low

`.todo/772` gave the sequence and alist SCANS one place that hoists a computed
`:test`/`:test-not`/`:key` (and every other keyword value) out of the loop and into
argument order: `LispMacroExpander.KeywordTail` (`.kb/sequence-designator-evaluation.md`).
The operators that were not in that pass still inline the form:

- `subst` / `nsubst` / `sublis` / `nsublis` -- `:test`, `:test-not`, `:key`
- `sort` / `stable-sort` / `merge` -- `:key` (and the predicate)
- `search` -- the whole set
- `reduce` -- `:key` (and `:initial-value`, `:start`, `:end`, `:from-end`)

ANSI is red on `subst.order.2`, `nsubst.order.1/2`, `sublis.order.1/2`,
`nsublis.order.1/2`, `sort.order.2`, `stable-sort.order.1/2`, `search.order.2`,
`reduce.order.2/3` (measure with `ansi-test/measure.sh sequences cons` and diff the
FAIL/ERROR names, the method `.kb/sequence-designator-evaluation.md` records).

## How to do it

Per operator it is three lines -- `KeywordTail.of(parts, start, prefix)`, `parts =
tail.parts()`, `return tail.wrap(...)`. What needs thought each time:

1. WHERE the positional arguments bind. `wrap` binds the non-literal ones first, so the
   expansion's own `let`/`do` bindings for them become variable copies; an expansion that
   instead inlines an argument into its loop (the defect `subsetp` had) must be fixed
   first, or the argument keeps being evaluated per element.
2. WHICH surface the interpreter uses. `sort`/`stable-sort` are runtime functions in
   `LispEvaluator` (they already read a nil `:key` through `presentKeyword`), so the
   expansion change moves only the compile paths and the ANSI numbers will not move.
   Check the `evalBuiltinMacro` dispatch before predicting a gain.
3. A designator that can be nil at runtime: `KeywordTail` defaults `:key` to
   `#'identity` and `:test` to `#'eql` where it binds, so a computed nil stops being a
   call of nil. An operator whose loop body reads the designator differently needs the
   same reading.

Found while closing `.todo/772` (2026-09-11).
