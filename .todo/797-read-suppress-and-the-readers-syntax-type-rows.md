# `*read-suppress*` and the reader's syntax-type rows

Difficulty: High

The `reader` chapter is the worst on the board -- **62 / 575 (10.8%)** on
2026-09-12 -- and two rows are 353 of its 513 failures.

## 1. `*read-suppress*` is not honoured -- 161 tests

```
FAIL READ-SUPPRESS.1 got (NONEXISTENT-PACKAGE::FOO) want (NIL 24)
FAIL READ-SUPPRESS.2 got (:||)                      want (NIL 1)
FAIL READ-SUPPRESS.5 got (123.45)                   want (NIL 6)
```

CLHS 2.2: when `*read-suppress*` is true the reader still PARSES a token
(consuming exactly the same characters) but returns `nil` and suppresses every
error a token would otherwise signal -- an unknown package, an unresolvable
symbol, an out-of-range digit. 124 FAIL + 37 ERROR.

Each expectation is a PAIR, `(nil <index>)`, so `read-from-string`'s missing
second value (`.todo/214`) is the other half of every one of these: neither row
alone turns a test green. Take them together or take 214 first.

## 2. The syntax-type surface -- 192 tests

`SYNTAX.*`: 101 FAIL + 91 ERROR, over the character syntax types
(`set-syntax-from-char`, escaped and multiple-escape characters, the
digit/alphabetic classification under a non-decimal `*read-base*`, the
constituent traits). Related, and separately owned: `readtable-case`
(`.todo/041`) and `read-preserving-whitespace` (26 tests).

## Why this is High

The reader is `reader/LispReader`, which the FORMATTER does not share
(`.kb/formatter.md`: `format` has its own lossless CST front end) but which the
compile path, `read-from-string`, `load` and the playground all do. A suppressed
read has to consume the same characters as a real one, so the change is inside
the token scanner rather than around it -- and `.kb/reader-case-upcase.md`,
`.kb/reader-features.md` and `.kb/read-time-constants.md` each pin behaviour the
scanner already owes.

## Before starting

Re-measure. `.todo/715` ranks from `ansi-test/results/logs/`, and the figures
above are TEST-level `FAIL`/`ERROR` lines from the 2026-09-12 run -- an upper
bound on what closing each row wins, never a floor (see 715, "How to count").
