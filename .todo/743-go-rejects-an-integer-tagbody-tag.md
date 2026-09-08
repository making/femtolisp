# `go` rejects an integer tagbody tag

Difficulty: Low

```lisp
(block nil (tagbody (go 10) (print 'bad) 10 (print 'ok)))
; => Unhandled condition: GO expects a tag: (GO 10)
; SBCL: prints OK
```

CLHS 5.3 (`tagbody`): a tag is "an integer or a symbol"; tags are compared with
`eql`. We accept only symbols.

## Cost

**51 tests** on the ANSI suite at `ca06bd9` (2026-09-08, TEST-level `ERROR`
lines in `ansi-test/results/logs/`), spread across `conditions`,
`data-and-control-flow`, `iteration` and `misc` -- every one of them the same
`(go 10)` from the suite's own `handler-case`/`restart` idiom, which uses a
numeric tag to name the abort point. It is the cheapest row of that size in the
whole report.

## How to do it

The tag check is one predicate; the tag table is a map keyed by the tag value.
The change is to key it by `eql` over symbols AND integers rather than by symbol
identity, in `LispEvaluator`'s `tagbody`/`go` and in the `Jvm`/`Wasm` `Tagbody`
compilers -- all four backends, so it needs a `ci-spec.yaml` case. `.kb/` topic:
whichever file owns `tagbody`/`go` (see `.kb/README.md`); note there that a tag
may be an integer and name the pinning test.

Found while re-reading the ANSI report for `.todo/715` (2026-09-08).
