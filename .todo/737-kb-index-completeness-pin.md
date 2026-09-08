# Pin that `.kb/README.md` lists every `.kb/*.md` exactly once

Difficulty: Low

`PathCitationTest` fails on a backticked repo-rooted path or a markdown link under `.kb/**`
that does not RESOLVE. It says nothing about a file that no line points AT: a topic file
added by a session that forgot the index line breaks no link, so nothing fails and the
index silently stops being an index.

Checked by hand 2026-09-08 and the index was complete (130 topic files, each named by
exactly one `[name.md](name.md)` link). That is the state to hold, not a result to rely on.

## What to add

A case in `PathCitationTest` asserting a BIJECTION between `ls .kb/*.md` minus `README.md`
and the set of files `.kb/README.md` links: every file listed at least once (the half no
test covers), and no file listed twice (a duplicate line means two sections claim the same
topic). Both directions in one assertion, with the offending names in the message.

Count LINKS, not filename-shaped text. Three shapes in the file defeat a naive
`[a-z0-9._-]*\.md` scan: the header writes `.kb/*.md` as prose, every entry contributes two
textual matches because the link text is the filename too, and the header paragraph names
four topic files (`string-index-cost.md`, `bfloat16.md`, `instance-syntax.md`,
`checkpoint-readers.md`) as evidence for a measurement. Matching `[name](name)` gives 130
entries for 130 files with no special cases.

## Not in scope

The prose-section citations (`` `.kb/jvm-export.md`, "What travels" ``) are a different
check -- the target file resolves, but the HEADING named in prose may not exist. Two such
citations had gone stale after the compaction and were repointed in the same pass; a
mechanical version of that check is worth its own item if it is wanted, since it has to
parse a quoted phrase out of prose and match it against the target's headings.
