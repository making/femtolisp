# `CiSpecE2eTest` leaves four files in the repository root

Difficulty: Low

The `uiop-file-io` case writes with RELATIVE names, so the working directory of
the run is the repository root:

```
?? u359-copy.txt
?? u359-forms.txt
?? u359-out.txt
?? u359.txt
```

None is git-ignored, so they sit in `git status` after every native E2E leg and
ride into a `git add -A`. Two sessions have now had to un-stage them by hand
(2026-09-11 on `develop` and in a worktree). `ansi-test/README.md` already states
the rule this breaks -- "a run must not leave that in the repository root" -- and
gives each chapter its own scratch directory for it.

The case is `uiop-file-io` in `src/test/resources/ci-spec.yaml` (search
`u359.txt`). The corpus runs as ONE concatenated program per backend, so the
files cannot simply move to a per-case directory without the case creating it;
the cheapest shape is a scratch directory the driver hands the run as its working
directory, which every other file-writing case would inherit. Check what else in
the corpus writes a relative path before choosing -- `files`/`streams`-shaped
cases may already do the same.

Whatever the fix, do NOT just add the four names to `.gitignore`: the next case
to write a relative path repeats it.
