# The runtime package API has no owner since `.todo/038` closed

Difficulty: High

`.todo/038-symbol-and-package-extensions.md` closed **completed** on 2026-08-15
and `.todo/715`'s ranked table still points at it for these names. It never
covered them, so they are unowned. `doc/*/guides/missing-features.md` records the
limitation; nothing owns closing it.

Measured on the ANSI suite at `ca06bd9` (2026-09-08, TEST-level `ERROR` lines in
`ansi-test/results/logs/`):

| missing | tests |
|---|---:|
| `make-package` | 217 |
| `set-up-packages` (the chapter's own helper, which needs `make-package`) | 56 |
| `delete-package` | 30 |
| `packagep` | 19 |
| `do-all-symbols` | 15 |
| `find-all-symbols` | 11 |
| `apropos` / `apropos-list` | 20 |
| `package-nicknames` | 9 |
| `package-error-package` | 6 |
| `rename-package`, `unintern` | 5 |
| `No such package` (a test naming a package `set-up-packages` would have made) | 25 |

Present and correct, so not in scope: `package-name`, `package-use-list`,
`package-used-by-list`, `package-shadowing-symbols`, `list-all-packages`,
`find-package`.

`packages` is the lowest-scoring chapter in the report (9.8%).

## The measurement is qualified

`.todo/739` §3: the driver skips every top-level `(in-package ...)`, so the
`packages` chapter runs in `COMMON-LISP-USER` throughout and part of its score
is the driver, not the implementation. Decide `.todo/739` §3 before treating
9.8% as the size of this gap -- but `make-package`'s 217 direct hits are real
either way.

## Why it is High

`defpackage` is a LITERAL top-level form resolved at read/compile time
(`LispPackageException: X is only supported as a literal top-level form`), which
is what lets the compile backends resolve every qualified name statically. A
runtime `make-package` is a different model: a package table that exists at run
time on all four backends, with the compile paths still resolving what they can
statically. That design decision is the item, not the operator list.

Found while re-reading the ANSI report for `.todo/715` (2026-09-08).
