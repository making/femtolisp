# 760. Compact `.todo/670` and close it

Difficulty: High

Decided 2026-09-10 by the human orchestrating both lanes: **`.todo/670` is compacted and then
CLOSED at the end of the current lane.** The umbrella's goal has been met -- a published
checkpoint runs from the file someone downloaded, in three formats, on all four backends -- and
what is left in the file is either recorded elsewhere already or is a rule that outlives the
plan. The work is deciding which of those two each paragraph is, and moving the second kind
somewhere it will be maintained.

This is not a tidy-up. 670 is 451 lines, it is the only home of sixteen standing rules that
other items cite **by number**, and one `.kb` file cites it by **line number**. Getting the
close wrong silently breaks citations that no compiler checks.

## The constraints, in the order they will bite

1. **The sixteen standing rules are cited by number and 670 declares the numbering fixed.**
   They are the most valuable thing in the file and they are not about this plan -- they are
   about running lanes, certifying runs and reading measurements. They need a `.kb` card of
   their own (`.kb/README.md`'s "Compile path and optimization" section already holds
   `measurement-probes.md` and `running-backends.md`; "Ecosystem and tooling" holds
   `directory-rename.md`, so process cards have a home). **Preserve the numbering** and
   re-point every citer: `.todo/756` (rule 4), `.todo/757` (rule 4), `.todo/758` (a standing
   rule, by description), `.todo/759` (rules 6 and 9). Check for more with a fresh grep, not
   this list -- rule 1 is about exactly that.
2. **`.kb/bfloat16.md:217` cites "`.todo/670` line 259".** A line-number citation into a file
   that is about to be rewritten and then deleted. `.kb/directory-rename.md:247-248` already
   records this happening to this same file once ("correct when written, and by 2026-09-06
   both the line numbers and the rows themselves were gone"), which makes repeating it the
   worse failure. **Replace it with the CLAIM it is citing, not a new pointer** -- a citation
   that survives is one that names what it needs.
3. **The other citers**: `.todo/390` (the checkpoint readers as consumers of the
   `file-position` gap), `.kb/bfloat16.md:76` and `:184`, `.kb/gpu.md:1429` and `:1474`. Each
   has to end up naming a fact or a live item rather than the umbrella. `PathCitationTest` is
   the machine half of this and it must be green.
4. **`.todo/731` (A-4) is still open and 670 is the only place its position is argued.** It is
   free-standing work -- the wasmtime landing-pad report and the toolchain floor, half of it a
   person's action, like `730` -- so it does not need an umbrella; but the reasoning in 670's
   A lane table ("a cross-backend pin that an unrelated later case can turn red is not a pin",
   and "the narrowing question is answered by the PIN, never by the version") is an argument
   `731` itself may not carry. **Read `731` first and move whatever is only in 670 into it.**
   Same check for `730`, and for `514` / `516` (the two macOS-box items 670 parks).
5. **The certification discipline is reusable and the plan is not.** "What a run certifies is
   failures, errors and the report-file SET -- never the totals", the two boxes' identical
   report-class lists, the three caveats about comparing two runs (the pre-`0e65326b` totals,
   the skip-vs-class difference, a skip count only meaning something against the same slice),
   and the line the section ends on -- *a verification owed by one party and skipped by
   everyone else is a gap that looks exactly like coverage until someone checks who actually
   ran it* -- all of that survives 670. The specific runs it certifies do not.

## What to cut rather than move

670's own rule 9 is the test: **an umbrella's status paragraph is evidence only where no child
covers the same fact.** Applied to itself:

- "Children: all closed" -- `.todo/history/2026-09.md` holds every row. One sentence, or none.
- "Findings, and where each one now lives" -- it says in its own first line that it is
  pointers, not records, and every target is a live `.kb` file or a live item. Cut it; verify
  each target actually still says the thing before cutting, since that is the one way this
  loses something.
- The two lane tables and the pool paragraphs -- they end with the lane.
- "The two machines" -- the box descriptions are worth a card; the per-box checkpoint
  inventory decays per box and rule 9 says that direction is the worse one. Decide whether it
  belongs anywhere at all or whether `.todo/677` already carries the paths.
- "What the measurements decided, width by width" -- check `.kb/bfloat16.md` and `.kb/gpu.md`
  first; if the verdicts are there, the table is a restatement.

## Done when

- 670 is removed and the history row names the removing commit, dated, status `completed`,
  with the model that did the work (the two-commit protocol -- removal first, row second, so
  `git show <commit>~:.todo/670-*.md` still recovers the text).
- Nothing that was only in 670 is only in git history: every rule, the certification
  discipline and any argument `731` needed are in a file that gets maintained.
- `grep -rn "670" .todo/ .kb/` finds no citation that has become a dangling pointer, and
  `PathCitationTest` is green.

## Not this item

Do not work anything the compaction turns up -- file it and leave it (670's own lane rule).
`731`, `730`, `758` and `759` stay open; this item re-homes what they need and does not take
them on. And **rule 7 applies to the new `.kb` card**: the rules were earned by both lanes, so
the human relays it to orchestrator A rather than one lane landing it alone.
