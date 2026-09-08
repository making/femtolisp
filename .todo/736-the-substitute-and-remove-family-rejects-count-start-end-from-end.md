# The substitute / remove family rejects `:count`, `:start`, `:end` and `:from-end`

Difficulty: Medium

`substitute`, `substitute-if`, `substitute-if-not`, their `n` twins, `remove` and
`count` accept only the keywords their expansions implement (`:test`/`:test-not`/`:key`
for `remove`, `:key` for the `-if` pair, `:test`/`:test-not`/`:key`/`:start`/`:end` for
`count`), so a standard call with `:count`, `:start`, `:end` or `:from-end` signals
`X expects keyword arguments ..., got: :COUNT`. Since `.todo/680` that is a catchable
`program-error` rather than a raw exception, which is what made the size of the gap
visible: on the ANSI `sequences` chapter (2026-09-08) it is the top two failure rows,

| count | reason |
|---:|---|
| 350 | `X expects keyword arguments :X, got: :X` |
| 228 | `X expects keyword arguments :X:X:X, got: :X` |

by operator and keyword (`ansi-test/results/logs/sequences.log`): `:count` on all six
substitute spellings (42-43 tests each) and on `remove` (15), `:start`/`:end` on the
substitute family (21-26 each), `:from-end` on the substitute family (16-24 each) and on
`count` (14).

## How to do it

1. `LispMacroExpander.expandSubstitute` / `expandSubstituteIf` / `expandNsubstituteIf`
   and `expandRemove` learn `:count` (stop replacing after N matches; a nil count is
   no limit), `:start`/`:end` (the subsequence the scan covers, elements outside it
   kept) and `:from-end` (with `:count`, the LAST N matches -- CLHS 17.2.1). The
   `buildPositionScan` shape already implements `:start`/`:end`/`:from-end` for the
   position family; `expandCount` has `:start`/`:end` and needs `:from-end` (which
   changes nothing for a count and can be accepted and ignored).
2. The interpreter's first-class twins (`positionScanValues`, the `Environment`
   `remove`, the `substitute` runtime helpers) take the same keywords, through the
   shared `keywordTailProblem` allowed sets, so `(funcall #'remove ... :count 1)`
   agrees with the call form.
3. Pin cross-backend with a ci-spec case; re-run `ansi-test/measure.sh sequences`
   (the rows above should vanish).

Found while closing `.todo/680` (2026-09-08).
