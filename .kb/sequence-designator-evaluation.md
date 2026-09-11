# When a `:test` / `:test-not` / `:key` designator form is evaluated

**Invariant: a designator form spelled in a sequence or alist call is evaluated ONCE,
before the scan, in the order the CALL spells its arguments (CLHS 3.1.2.1.2.3) -- unless
it is a LITERAL, which stays inlined in the loop body because evaluating it is not
observable and the compilers' function-designator normalization has to see it there.**

One place decides it for every scan: `LispMacroExpander.KeywordTail`. `of(parts, start,
prefix)` reads a call's keyword tail, hoists what must not stay inlined, and answers a
REWRITTEN `parts` (each hoisted form replaced by its variable) plus `wrap(body)`, the
`let` chain to put around the expansion. An expander's whole share is three lines: build
the tail, take its `parts`, wrap its result.

## What is a literal, and why the distinction is not cosmetic

Inlined: a self-evaluating atom, `(quote x)`, `(function x)`, a `lambda`, and the symbols
`t`/`nil`/a keyword. Hoisted: everything else, a BARE SYMBOL INCLUDED -- it can be a symbol
macro (`define-symbol-macro`), and CL evaluates the value form once whatever it is.

A literal has to stay where it is: `#'name` in the loop body is what
`normalizeFunctionDesignator` and the two compilers turn into a direct call. A computed
form in the same position is wrong twice over -- once per ELEMENT instead of once, and
inside the loop instead of in argument order. A call whose keyword values are all literal
therefore hoists NOTHING (not even a positional argument) and expands byte-identically to
the way it did before, so no existing call site pays.

## What binds, and in what order

`wrap` emits, outermost first: the positional arguments (only the non-literal ones), then
one binding per hoisted keyword value, in SOURCE order. A keyword spelled twice binds BOTH
values and uses the first (ANSI's `member-if.order.2`). The positional arguments have to
come along: the scans bind them INSIDE a sequence dispatch or a `do` loop, which the
keyword values would otherwise have run before.

Hoisting also DEFAULTS a designator, because the expansion can no longer read the literal
that used to tell it so -- ANSI passes a computed nil for `:key` (`subsetp.order.2`):

- `:key` binds as `(or form #'identity)`, `:test` as `(or form #'eql)`. The `or` short-
  circuits, so a non-nil designator allocates nothing and the loop body is unchanged.
- a `:test-not` that the scan will USE (no `:test` beside it) binds as the COMPLEMENTED
  test and is rewritten into the `:test` slot, so the match form is the same `funcall`
  either way. The negation is spelled out as a two-argument lambda rather than delegated
  to `complement`, whose expansion answers a ONE-argument lambda on purpose
  (`expandComplement`: a variadic one would need `apply`, and `apply` drags the eval
  runtime into every compiled program that uses it).
- The injected `#'identity` / `#'eql` need no `#'identity` in the SOURCE: the wrapper
  reference gate runs over the EXPANDED tree, so `BuiltinFunctionWrappers` emits them.

## Who is covered

The scans that carry the `:test`/`:test-not`/`:key` set: `expandMember`, `expandAssoc`,
`expandRassoc`, `buildPositionScan` (the `position`/`find` six),
`expandCount`/`expandCountIf`, the `remove`/`delete`/`substitute`/`nsubstitute` fifteen
(over `SeqScanScaffold` -- `.kb/sequence-bounding-keywords.md`),
`expandRemoveDuplicates`, and the set operations
`expandAdjoin`/`expandUnion`/`expandIntersection`/`expandSetDifference`/`expandSubsetp`.
NOT covered: `subst`/`sublis` (`:test`/`:key`), `sort`/`stable-sort`/`merge` and
`search`/`reduce` (`:key`) -- ANSI's `subst.order.2`, `sort.order.2`, `search.order.2`,
`reduce.order.2/3` are still red (`.todo/777`). Each is `KeywordTail.of(parts, start,
prefix)` plus `tail.parts()` plus `tail.wrap(...)`; what needs checking is where their
positional arguments bind and which surface the interpreter uses for them.

Two argument-order defects in the set operations were the same bug without a keyword and
are fixed beside it: `union` evaluated list-b before list-a (its `do` bound the cursor
first), and `subsetp` inlined list2 into the inner `member` call, so it was evaluated once
per element of list1.

**The interpreter is not one surface here.** `member`/`assoc` are RUNTIME functions in the
interpreter (`LispEvaluator`), which evaluate their arguments before the call and were
already in order -- but read a nil `:key` VALUE as a function to call until
`presentKeyword` replaced `optionalKeywordArg` there. Everything else in the list above
reaches the expansion through `evalBuiltinMacro`, so the interpreter and the three compile
paths run the same hoist.

## What it moved (ANSI, interpreter, 2026-09-11)

`ansi-test/measure.sh sequences cons`, suite `ca06bd9`:

| chapter | before | after |
|---|---|---|
| sequences | 2,861 / 3,287 (87.0%) | 2,891 / 3,287 (88.0%) |
| cons | 1,057 / 1,879 (56.3%) | 1,082 / 1,879 (57.6%) |

A name-by-name diff of the FAIL/ERROR sets: **55 tests fixed, zero tests that passed
before failing after.** The `*.ORDER.*` tests of `count`, `remove`, `delete`,
`substitute`/`nsubstitute` (six spellings), `position`/`find` (six), `rassoc`,
`adjoin`, `union`, `intersection`, `set-difference` and `subsetp` all answer ANSI's
counters exactly now.

What the order tests still fail on, each a different gap:

- `remove.order.2` / `delete.order.2` / `adjoin.order.2` pass `(complement #'eq)` as a
  two-argument `:test-not`: `complement` answers a ONE-argument lambda (`.todo/774`).
- `member-if.order.2`, `assoc-if*.order.*`, `rassoc-if*.order.*`: the `-if` spellings of
  `member`/`assoc`/`rassoc` take no `:key` at all (`.todo/776`).

A third gap stood in that list until 2026-09-11:
`remove-duplicates.order.1/2` and `delete-duplicates.order.1/2` want
`:start`/`:end`/`:test-not` and a COMPUTED `:from-end`, which `expandRemoveDuplicates`
rejected. Fixed (4 more tests, sequences 2,891 -> 2,895) --
`.kb/sequence-bounding-keywords.md`, "the window bounds what is CONSIDERED".

## Pinning tests

- `LispMacroExpanderTest.aComputedSequenceDesignatorBindsOnceBeforeTheScan` -- the literal
  stays inlined, the computed one binds once in source order, the `:test-not` shape.
- `LispMacroExpanderTest.aBoundedSequenceScanEmitsOnlyTheScaffoldingItsKeywordsAskFor` --
  the byte-identical expansion a literal call keeps.
- `LispEvaluatorTest.evalSequenceScansEvaluateAComputedDesignatorOnce` -- the ANSI order
  counters, the duplicate keyword, the computed nil designator, call position and
  first-class.
- ci-spec `sequence-designator-order` -- the same counters on all four backends.
