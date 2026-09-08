# 748. The corpus's wild-pathname case walks the whole project tree

Difficulty: Low

Filed 2026-09-08 from orchestrator A's certification run at `8b3adb1e8` (`.todo/670`). It
is the second instance of a defect this repo has already paid for once, and the first one
whose blast radius was a RED rather than a slow test.

**`ci-spec.yaml`'s `wild-pathnames` case ends with `(directory "./**/*.txt")`, and `**` is
`:wild-inferiors`: it walks every directory below the process working directory**, which
for every in-process corpus runner is the PROJECT ROOT. The case's assertion is stable --
a `remove-if-not` keeps only the two `./wpc-*.txt` files the case writes itself -- so what
the walk costs is invisible in the expectation and unbounded in fact.

On dorian the tree holds `.claude/worktrees/`: 9991 of the 10375 `.txt` files under the
root are in other agents' checkouts. The resulting list is long enough to overflow
`append` (`.todo/749`), so `JvmClassShakerCorpusTest` -- which runs the whole corpus
in-process -- errors with a `StackOverflowError` whose entire stack is `Test._append`.

**The red is a property of the box, not of the tree.** The same commit is green in a fresh
worktree under `/tmp` and red in the main checkout; a bisect over the lane's commits
"found" a culprit and was measuring the WORKTREE, not the commit. The controlled
experiment is the one that settles it: hold the tree constant and vary only the file
(`git checkout <older> -- src/test/resources/ci-spec.yaml` in the main tree, still red).

## Why this is the same bug as `0e65326b`

`LispFormatterTest` used to walk `Path.of(".")` and format every `.lisp` under
`.claude/worktrees/`, so one term of its comparison was how many agents had run on the box
recently (`.todo/670`, "Three things a reader needs"). That was fixed by scoping the walk.
**The corpus then re-introduced the same unbounded walk in Lisp**, and the sibling case
`directory-listing-and-uiop-walkers` right above it states the discipline in its own
comment -- "The entries are written by this case so the answer does not depend on what
else the run directory holds" -- which the `**` case does not follow.

So the rule is not "do not walk `.`" but: **a test that reads the filesystem must bound
what it reads by construction, not by filtering afterwards.** A filter fixes the
assertion; it does not fix the work.

## The constraint that makes it not a one-liner

The case's comment records why it walks the CWD at all: `**/` matching ZERO directory
levels is the branch being pinned, and **neither WASM backend can create a directory**, so
the case cannot build a subtree to walk. Scoping it therefore needs either a directory the
harness creates for all four backends, or a pattern anchored to a prefix the case owns
(`"./wpc-**/*.txt"` walks nothing that exists) with the zero-level branch pinned some other
way. Decide which, and say why in the case's comment -- the next person will otherwise
re-widen it.

## Done when

- The corpus case's filesystem walk is bounded by construction, and the zero-level
  `**/` branch is still pinned on all four backends.
- The remaining `directory` / `uiop:` walkers in `ci-spec.yaml` are checked for the same
  shape (`"./*.*"` and `(uiop:directory-files ".")` in the sibling case read the root's
  entries too -- bounded by depth, not by construction).
- `JvmClassShakerCorpusTest` passes in the main checkout on a box carrying agent
  worktrees. Fixing `.todo/749` alone would make the crash go away and leave the walk;
  that is not this item being done.
