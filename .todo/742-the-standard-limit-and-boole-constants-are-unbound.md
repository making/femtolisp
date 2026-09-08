# The standard limit constants, `boole-*`, `*gensym-counter*` and `*random-state*` are unbound

Difficulty: Low

Probed on the interpreter at `2f07b480e`:

```lisp
array-rank-limit          ; => The variable ARRAY-RANK-LIMIT is unbound
call-arguments-limit      ; => unbound
lambda-parameters-limit   ; => unbound
multiple-values-limit     ; => unbound
boole-1 boole-and ...     ; => unbound (all 16)
*gensym-counter*          ; => unbound
*random-state*            ; => unbound
```

`array-dimension-limit`, `array-total-size-limit`, `char-code-limit`,
`internal-time-units-per-second`, `most-positive-fixnum` and the float constants
ARE bound (through `CL_READ_TIME_CONSTANTS`, which is its own problem --
`.todo/679`). These are simply absent.

## Cost

Direct, on the ANSI suite at `ca06bd9` (2026-09-08, TEST-level `ERROR` lines):
`call-arguments-limit` 39, `array-rank-limit` 12, `lambda-parameters-limit` 5,
`multiple-values-limit` 3, `*boole-vals*` 4 (the aux list `boole-*` builds),
`*random-state*` 7, `*gensym-counter*` 1.

**Indirect, and much larger:** the suite's `universe.lsp` -- loaded before EVERY
chapter -- builds `*array-dimensions*` from `array-rank-limit`, and `*arrays*`
from `*array-dimensions*`, and `*universe*` / `*mini-universe*` from `*arrays*`.
`array-rank-limit` alone is therefore one of the five links that keep
`*UNIVERSE*` (175 tests) and `*MINI-UNIVERSE*` (233) unbound in every chapter.
`.todo/715` has the whole chain; the other four links are `.todo/679`
(`'pi` reads as a double), `(setf (logical-pathname-translations ...))`,
one-argument `(compile nil)` and `find-method`.

## How to do it

`boole-*` are 16 integer constants and the `boole` function that consumes them
belongs to `.todo/037`; the constants can land without it. The limits are plain
integers, but `call-arguments-limit` / `lambda-parameters-limit` /
`multiple-values-limit` / `array-rank-limit` differ per backend the same way
`most-positive-fixnum` does, so the value must be seeded per backend
(`Environment.createGlobal` for the interpreter plus the equivalent seed on the
JVM and both WASM paths) and NOT added to `CL_READ_TIME_CONSTANTS` -- that set is
what `.todo/679` exists to unwind. `*gensym-counter*` must be the variable
`gensym` actually reads (`.kb/` gensym prefix note in `.todo/156`);
`*random-state*` the one `random` reads.

Then: `PackageRegistry.CL_SYMBOLS`, a `ci-spec.yaml` case for the four-backend
agreement (values legitimately differ per backend), and the constants' rows in
`doc/{en,ja}/reference/`.

Found while re-reading the ANSI report for `.todo/715` (2026-09-08).
