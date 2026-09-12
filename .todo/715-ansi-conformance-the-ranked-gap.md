# ANSI conformance: what the suite says to fix next

Difficulty: Medium

This item is the READING of `ansi-test/results/interpreter.md`: which gap is
worth closing, in what order, and which todo owns it. The numbers themselves
live in that report and are not duplicated here -- only the ordering, the owner
and the reason.

**Re-measure before you read.** `ansi-test/measure.sh` with no argument rewrites
the checked-in baseline; pass every chapter name instead and it writes
`results/partial.md`, which is what a local re-read wants. Then rank from
`ansi-test/results/logs/*.log`, not from the report's reason table -- see
"How to count" below.

## Baseline (re-measured 2026-09-08, suite revision `ca06bd9`)

**10,809 / 19,461 tests pass (55.5%)** -- 2,823 wrong values, 5,829 signalled,
586 top-level forms lost. A full local re-run reproduced the checked-in report to
within 2 tests (10,808 / 2,821 / 5,830; the drift is `random-state`, and the
daily CI refresh landed 10,809 the same day), so the committed series is
trustworthy and was NOT overwritten.

Against the previous reading in this file (41.0%, 7,254 / 17,689, 2,229 lost
forms): `.todo/680` made argument-shape errors a catchable `program-error` and
`.todo/681` stopped booking a raw exception out of a `deftest` as a lost form, so
BOTH the numerator and the denominator moved. Every per-chapter rate quoted in
the old table is dead; do not carry one forward.

## How to count

The report's "Most frequent failure reasons" table merges two different units:
`ERROR <test>` lines, one per TEST, and `%%%EVAL`/`%%%READ` lines, one per lost
FORM **per chapter** -- and the aux files are loaded by all 25 chapters. One
failing aux form therefore appears 25 times beside rows that are test counts.
`The variable *UNIVERSE* is unbound` reads 202 in the report and is 175 tests.
Filed as `.todo/739` section 4. **Every number below is TEST-level `ERROR`/`FAIL`
lines from `results/logs/`.**

## The ranking

### 1. Two wrong-value clusters that need no new operator

| what | tests | owner |
|---|---:|---|
| `read-from-string` has no index second value -- 184 of the `reader` chapter's 285 failures | 184 | `.todo/214` |
| `subtypep` has no valid-p second value -- 112 of the 142 failing `SUBTYPEP.*` | 112 | `.todo/214` |
| a macro EXPANDER's secondary values leak into the expansion's value (the suite's `expand-in-current-env`, used by 132 of its files) -- `got (X T) want (X)` | 264 | `.todo/213` |

`.todo/213`'s 264 is the largest single FAIL cluster in the report and was
unpriced until now; `.todo/214` owns the two next largest. Both operators in
`.todo/214` already exist and answer their primary value correctly. **These three
rows are the cheapest points on the board.**

### 2. The `universe.lsp` cascade -- 504 tests behind five links

`universe.lsp` is loaded before EVERY chapter and builds `*universe*` /
`*mini-universe*` by appending sixteen lists. Five of them fail, so both are
unbound everywhere: `*MINI-UNIVERSE*` 233 tests, `*UNIVERSE*` 175, `*REALS*` 50,
`*NUMBERS*` 30, `*FLOATS*` 15.

The full chain, form by form, is in `.todo/679`. Its links: `.todo/679` (`'pi`
reads as a double, High), `.todo/742` (`array-rank-limit` unbound, Low),
a `setf` place for `logical-pathname-translations` (unowned), one-argument
`(compile nil)` (`.todo/042`), and `find-method` (out of scope, `.kb/clos.md`).
**`.todo/679` alone recovers ~95 of the 504** -- its own "618 behind one fix"
figure is corrected there. Land `.todo/742` first: it is a Low-difficulty
constant on the same chain and makes `.todo/679`'s recovery visible.

Closing this ADMITS ~500 tests that may then fail. That is progress, and it is
why the report keeps the lost-form column beside the rate.

### 3. Operator families, by tests billed

| family | tests | owner |
|---|---:|---|
| `substitute`/`remove`/`count` reject `:count`/`:start`/`:end`/`:from-end` -- the report's top two rows | 578 | `.todo/736` |
| ~~the cons set / tree family (`nunion`, `nset-*`, `nsubst*`, `assoc-if-not`, `nbutlast`, `list-length`, `tailp`, `get-properties`, ...)~~ -- DONE 2026-09-12, worth **+514**, MORE than the 458 this row priced (see "Worked so far") | 458 | `.todo/740` |
| bit-array ops plus `bit-vector-p` / `simple-bit-vector-p` / `array-in-bounds-p` / `upgraded-array-element-type` | ~400 | `.todo/043`, `.todo/180` |
| the runtime package API (`make-package` 217, `delete-package`, `packagep`, `do-all-symbols`, `find-all-symbols`, `apropos*`) | 370 | **`.todo/741`** (new) |
| stream constructors and `open`'s `:if-exists` / `:direction` / `:element-type` | 224 | `.todo/387` |
| complex numbers (`#C` 56, the type specifier 40, the operators 92) | 188 | `.todo/037` |
| the integer logical family plus `float-radix` (80 on its own), `rational`, `rationalize`, `realp` | ~180 | `.todo/037` |
| ~~`find-class` has no `ARRAY`/`VECTOR`/`BIT-VECTOR`/`NUMBER` class~~ -- DONE 2026-09-11, worth **+41**, not the 156 this row priced (see "Worked so far") | 41 | `.todo/744` |
| `pprint-fill`/`-linear`/`-tabular`/`formatter` (81) and a `setf` place for `readtable-case` (56) | 137 | `.todo/041`, `.todo/001` |
| `(go 10)` -- an integer tagbody tag is rejected | 51 | **`.todo/743`** (new) |

`.todo/033` and `.todo/038` were the owners this table used to name for the cons
and package rows. **Both closed `completed` on 2026-08-15 without covering
them**, so those rows had no owner at all until `.todo/740` / `.todo/741` were
filed for this reading. Check an owner still exists before quoting one.

### 4. Billed by the suite, DECIDED AGAINST -- do not read these as gaps

- MOP reflection: `find-method`, `compute-applicable-methods`,
  `ensure-generic-function`, `add-method`, `define-method-combination` (~70
  tests). `.kb/clos.md`, "Out of scope": classes are compile-time-static and the
  dispatch tables depend on it.
- `slot-value` as a first-class function (53). It is in `CL_MACROS` by design
  (`.kb/clos.md`); `SLOT-VALUE is a macro or special operator, not a function` is
  the model working.
- `compile-file` / `compile-file-pathname` (29): "no file compiler -- a program
  is compiled whole".
- `class-precedence-list-foo` (63, all in `types-and-classes`): the suite builds
  it with `#.` read-eval over `(:method-combination list)`. It is one aux form,
  not an operator.
- `packages` at 9.8% is partly the DRIVER: it skips every `(in-package ...)`, so
  the chapter runs in `COMMON-LISP-USER` throughout (`.todo/739` section 3).
  Settle 739 before treating that rate as a capability measurement.

## The second instrument: the _Practical Common Lisp_ corpus

Peter Seibel's book code -- twelve ASDF systems of ordinary 2005 Common Lisp,
diffed byte for byte against SBCL. The standing verdict is `.kb/asdf.md`, "The
_Practical Common Lisp_ book corpus". It ranks by WHETHER A PROGRAM RUNS AT ALL,
while the suite ranks by TESTS LOST, which over-weights operator families nobody
calls. **The judgment axis has not changed: an item BOTH instruments name is the
one to take first.**

As of 2026-09-08 the corpus needs no shim and no replacement `.asd`, and names
exactly one live gap: **`.todo/041`'s missing right margin**, which three systems
(`simple-database`, `test-framework`, `pathnames`) still differ by. The suite
independently puts `.todo/041` in the table above (`printer` at 40.7%, the
`pprint-*` layout operators plus `setf readtable-case`). It is the only item both
instruments name, and on the corpus's own axis it is the last one standing.

Everything the corpus previously put ahead of the suite has closed: the reader
could not read `.4` (`.todo/621`), `nreverse` on a vector answered nil
(`.todo/602`), `delete`/`nsubstitute` were vector no-ops (`.todo/623`), a `.asd`
with its own component class (`.todo/625`), line-oriented `read` (`.todo/624`),
and the ordinal accessors `fifth`..`tenth` -- chapter 32's `profiler` is
byte-identical again. The three `net.aserve` systems are decided against
(`.kb/asdf.md`).

What the corpus still says is NOT urgent, against the suite's ranking: the
runtime package API (`.todo/741`) and complex numbers. The corpus uses only
`defpackage`/`in-package`, and both work.

## Worked so far

- **2026-08-12** -- `find-symbol`/`intern` answer the accessibility status as
  their second value, `symbol-plist` exists. `symbols` 4.2% -> 58.4%, the
  largest single move the report has recorded; it is 69.5% today. Three lowering
  bugs had to go first, all on `(find-symbol "CAR" 'common-lisp)`; mechanics and
  the deviation that remains are in `.kb/symbol-runtime-api.md`.
- **2026-08-15, `.todo/379`** -- the built-in seam in `LispEvaluator.apply` wraps
  an escaping `IndexOutOfBounds` / `NegativeArraySize` / `Arithmetic` /
  `ClassCast` into a `LispEvalException`. Recorded then and STILL OPEN:
  `(elt (list 1 2) 5)`, `(nth -1 ...)` and `(coerce "abc" 'integer)` answer nil
  where CL signals -- a silent-nil family the seam cannot see.
- **2026-08-15, `.todo/380`** -- typed built-in errors (`type-error`,
  `division-by-zero`, `unbound-variable`, `undefined-function`) instead of one
  `simple-error`. Recorded then and still open: `reader-error`,
  `print-not-readable`, `storage-condition` and the `floating-point-*` four are
  not SEEDED, so a `(:use #:cl)` package's `'reader-error` can never match. Seed
  one WITH a signaling site, not before one.
- **2026-09-08, `.todo/680`** -- argument-shape errors are a catchable
  `program-error`. This is what made `.todo/736`'s 578 tests visible; the raw
  `IllegalArgumentException` shape this file used to rank is gone.
- **2026-09-08, `.todo/681`** -- a raw exception escaping a `deftest` is booked
  as that test's error, not as a lost form. Lost forms 2,229 -> 586, and the
  denominator grew by 1,770.
- **2026-09-11, `.todo/744`** -- `find-class` answers for the built-in class
  lattice (`array`, `vector`, `bit-vector`, `number`, `real`, `rational`,
  `sequence`, `list`, `structure-object`, `built-in-class`) and `class-of`
  narrows an array to `vector`/`array`. Measured on `arrays` + `objects` +
  `types-and-classes`: **+41 tests** (`arrays` 650 -> 690, errors 619 -> 575;
  `types-and-classes` 280 -> 281; `objects` unchanged). **The row above priced it
  at 156 and was wrong**: it counted TEST-level `ERROR` lines naming the class,
  but once the class resolves the test often fails for a SECOND reason -- 14
  `BIT-VECTOR.*` tests went ERROR -> FAIL because there is no distinct bit-array
  representation (`.todo/043`), and the `built-in-class`/`structure-object`/`real`
  rows sit behind `*universe*` (`.todo/679`) or `class-precedence-list`.
  **An ERROR-line census is an UPPER bound on what closing the gap wins**; when
  ranking from `results/logs/`, discount a row whose tests assert something else
  as well. Mechanics and the remaining 8 lines (`broadcast-stream` 7,
  `standard-generic-function` 1): `.kb/clos.md`.

- **2026-09-12, `.todo/740` + `.todo/776`** -- the seventeen missing cons set /
  tree operators (`nunion`, `nintersection`, `nset-difference`,
  `nset-exclusive-or`, `nsubst`/`-if`/`-if-not`, `nsublis`, `subst-if`,
  `subst-if-not`, `member-if-not`, `assoc-if-not`, `rassoc-if-not`, `nbutlast`,
  `list-length`, `tailp`, `get-properties`), plus `:key` on the six
  `-if`/`-if-not` alist scans. `cons` **1,108 -> 1,622 / 1,879** (59.0% ->
  86.3%), errors 626 -> 77; **514 tests fixed, zero regressed**;
  `sequences` 2,933 -> 2,937. **The row above priced it at 458 and was LOW**:
  the census could only count names that were UNDEFINED, and six operators
  recorded as "already present and correct" (`subsetp`, `set-difference`,
  `sublis`, `union`, `intersection`, `butlast`) were losing another ~90 tests to
  a first-class surface that took no keywords and to `butlast`'s missing count.
  **An ERROR-line census is an upper bound on the NAMED gap, not on the chapter**
  -- it cannot rank what is present and wrong. Mechanics:
  `.kb/cons-set-and-tree-operators.md`.

## Reading caveat

586 top-level forms are still lost, so every chapter is measured optimistically;
`streams` (61) and `printer` (53) most of all. Closing a gap can LOWER a
chapter's rate by admitting the tests behind it -- that is progress, and the
reason the report keeps the lost-form column next to the rate.

`ansi-test/README.md`, "What the numbers are not": the suite tests full ANSI CL,
which rontolisp does not set out to be. A failing test is a statement about the
standard, not automatically a bug worth fixing. Section 4 above is that filter
applied.
