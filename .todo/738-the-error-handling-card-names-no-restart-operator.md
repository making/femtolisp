# The error-handling card describes the restart stack and names none of its operators

Difficulty: Low

`.kb/error-handling.md` has a whole section on the mechanism -- "Phase 4 --
handler-bind + the restart stack", with the invariant that "the restart system
is ONE shared Lisp-level lowering in `LispMacroExpander`", the `(%restart name
invoker report interactive test)` record shape, the fresh-cons `catch`/`throw`
transfer, and the restart-mode gate that `cerror` rides. What it never names is
which operators that lowering serves, or where the lowering starts.

Grep `.kb/` for each of them and only one has a hit anywhere in the tree:

| operator | hits in `.kb/**` |
|---|---|
| `restart-case` | the `cerror` lowering line only |
| `restart-bind` | 0 |
| `invoke-restart` | 0 |
| `find-restart` | 0 |
| `compute-restarts` | 0 |
| `with-simple-restart` | 0 |

All six are in `LispNames` and have a reference page each
(`reference/macros/{restart-case,restart-bind,with-simple-restart}.md`,
`reference/functions/{invoke-restart,find-restart,compute-restarts}.md`), and
`LispMacroExpander` carries seven `expandRestart*`-family entry points. So a
reader who arrives at the card -- the file `CLAUDE.md` sends them to before
changing behaviour in this area -- cannot get from "the restart system" to the
names it is made of, or to the method to set a breakpoint in.

Found while closing `.todo/695`'s identifier-preservation audit: these names were
in the pre-compaction version of the card and the rewrite dropped them. A name
that a reader cannot regenerate from a convention is load-bearing; these are not
`Jvm<Name>Compiler`-shaped.

## What to do

Add the operator names and the lowering entry point to the existing Phase 4
section. A row per operator is not wanted -- the card is a card. One or two
lines naming the six and the `LispMacroExpander` method that lowers them is the
whole change.

Two names came out of the same audit unresolved; settle each in passing:

- `program-error` -- now a reporting condition class
  (`.todo/680`, `ClosRegistry`), and the card's condition-class list should say so.
- `stream-error-stream` -- appears in neither `.kb/**` nor `doc/**`. Decide
  whether it is implemented-but-undocumented (then it needs a reference page,
  which is a separate item -- file it, do not write it here) or a dead name.

## Do not

- Do not touch `src/`. This is a documentation item.
- Do not re-expand the card. `.todo/695` measured that ~42% of the
  pre-compaction size is the resting size; this adds lines, it does not reopen
  the narrative.

## Done when

- `./mvnw -Dtest=PathCitationTest test` is green.
- Every operator in the table above appears in `.kb/error-handling.md`.
