# `remove-duplicates` / `delete-duplicates` reject `:start` / `:end` / `:test-not`

Difficulty: Medium

`LispMacroExpander.expandRemoveDuplicates` accepts `:test`, `:key` and `:from-end` only:

```
REMOVE-DUPLICATES expects keyword arguments :TEST/:KEY/:FROM-END, got: :START
```

CLHS gives both spellings the full 17.2.1 set (`:from-end`, `:test`, `:test-not`, `:start`,
`:end`, `:key`). ANSI's `remove-duplicates.order.1/2` and `delete-duplicates.order.1/2`
fail on exactly that, and the census of `X expects keyword arguments` rows in
`ansi-test/results/interpreter.md` will say how many plain (non-order) tests follow.

## How to do it

1. The `:start`/`:end` window bounds which elements are CONSIDERED; elements outside it are
   kept verbatim and are not compared. That is not what `SeqScanScaffold` does (it skips
   the excluded elements entirely for `remove`), so read `.kb/sequence-bounding-keywords.md`
   before reaching for it and decide whether the scaffold can serve this shape or the
   expansion needs its own index guard around the inner `member` call.
2. `:test-not` is the `TestSpec` pair the rest of the family already carries -- the
   expansion forwards it to the inner `member` through `memberCallForm`.
3. `:from-end` here must stay a LITERAL: the direction picks which duplicate is kept, and
   the expansion's shape (which list the inner `member` scans) depends on it. The current
   `IllegalArgumentException` for a computed one is the existing contract; if that has to
   change, it becomes a runtime branch over two loops.
4. The interpreter reaches this through `evalBuiltinMacro`, so one expansion covers all
   four backends; pin it in ci-spec beside `sequence-bounding-keywords`.

Found while closing `.todo/772` (2026-09-11).
