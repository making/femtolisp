# 751. Complex numbers: core value, reader/printer, interpreter arithmetic

Difficulty: High

Spike filed 2026-09-09 (SBCL 2.6.5, host `/opt/homebrew/bin/sbcl`; every probe
wrapped in `timeout 20`, rontolisp probes in `timeout 30`). This is step 1 of the
complex-number split; 752 (JVM), 753 (WASM GC + scalar), 754 (type system, corpus,
docs) build on it. On completion, update the "Complex numbers" section of
`.todo/037-number-extensions.md` (it currently says "defer until there's a concrete
use case" -- this split IS the use case).

## SBCL semantics measured (the contract to match)

- Canonicalization: `(complex 1 0)` -> `1` (demoted, `(eql 1 #c(1 0))` is T);
  `(complex 2.0 0)` -> `#C(2.0 0.0)` (integer zero coerced to float zero, stays
  complex -- float zeros never demote); `(eql 2.0 #c(2.0 0))` is NIL.
- Contagion: `(+ #c(1 2) 1.5)` -> `#C(2.5 2.0)`; `(+ #c(1 1/2) #c(1 1/3))` ->
  `#C(2 5/6)` (exact rationals stay exact).
- Reader: `#C(1 2)` reads; parts must be REAL -- `#C(#C(1 2) 3)` signals.
  Printer: `#C(1/2 1/3)` prints as `#C(1/2 1/3)` (each part printed as a real).
- Predicates: `complexp`/`numberp` T, `realp` NIL, `(typep #c(1 2) 'real)` NIL;
  `(type-of #c(1.0 2))` is `(COMPLEX SINGLE-FLOAT)`;
  `(upgraded-complex-part-type 'integer)` is `RATIONAL`.
- Arithmetic: `(- #c(1 2))` -> `#C(-1 -2)`; `(abs #c(3 4))` -> `5.0` (real);
  `(sqrt -1)` -> `#C(0.0 1.0)`; `(= #c(1 2) #c(1 2))` and `(zerop #c(0 0))` are T.
- Ordering/comparison signals: `(minusp #c(1 2))`, `(< #c(1 2) #c(3 4))` are ERR
  (catchable type-error, cf. `.kb/error-handling.md` "A non-number reaching
  arithmetic signals a catchable type-error").
- `(conjugate #c(1 2))` -> `#C(1 -2)`; `(realpart 5)` -> `5`, `(imagpart 5.5)` -> `0.0`
  (realpart/imagpart accept reals; imagpart of a float is float zero).

## Current rontolisp state

- No `LispComplex` type; `LispVal` permits exactly
  `LispInteger, LispBigInteger, LispRatio, LispDouble, ...` (`LispVal.java:6`).
- `complex` is lite-only: `expandComplexLite` yields the real part for zero
  imaginary, else signals "complex numbers are not supported" (verified live:
  `(complex 1 2)` signals). Call sites: `LispEvaluator.java:5686`,
  `JvmExprCompiler.java:1024`, `WasmExprCompiler.java:1339`.
- No `#C` reader support (`reader/` has no SharpC; `#L`/`#N@(` are the dispatch
  precedents in `LispLexer.java`).
- No `complexp`/`realp`/`realpart`/`imagpart`/`conjugate`/`phase` anywhere.

## Work

1. New `rontolisp/LispComplex` record with canonicalizing factory `valueOf(real,
   imag)`: real parts restricted to Integer/BigInteger/Ratio/Double (nested
   complex rejected, like SBCL); rational-zero demotes to the real, float-zero
   stays complex (int zero coerced to `0.0` when the other part is float).
   Add to `LispVal` permits; `print()` as `#C(<real> <imag>)`.
2. Reader: `#C` dispatch in `LispLexer` + `readSharpC` in `LispReader`
   (two-element list of reals; signal `LispReadException` otherwise). Check
   `format`'s CST front end too -- it needs source verbatim, but `#C` must survive
   `rontolisp format` (`.kb/formatter.md`).
3. `LispEquality`: `eql`/`equalp`/`=` over complex (exact part-wise; demoted
   values compare as reals, so `(= 1 #c(1 0))` is T via canonicalization).
4. `eval/Environment` arithmetic: `+ - * /` (incl. 1-arg `-`/`/`), `1+`/`1-`,
   `abs`, `sqrt` (negative real -> complex), `expt`/`exp`/`log`/trig path for
   complex operands, `phase`, `conjugate`, `realpart`/`imagpart` (accept reals),
   `complexp` (new), `realp` (new: T for Integer/BigInteger/Ratio/Double),
   `numberp` (include complex), `zerop` (both parts zero). Keep the
   `hasDouble`/`asDouble` fast paths for real-only calls; complex goes through a
   real-parts helper, never `asDouble` on the whole value.
5. `LispNames` constants + `PackageRegistry.CL_SYMBOLS` entries for every new
   name; `Environment.createGlobal()` definitions; `BuiltinFunctionWrappers`
   entries (per AGENTS.md "Adding a Built-in Function" steps 1, 2, 5).
6. Delete `expandComplexLite` and its three backend call sites once real support
   lands here (752/753 rewire the compilers to the real thing; coordinate so no
   backend regresses through the lite path in between).
7. Tests first (bug-fix rule): `LispEvaluatorTest` cases reproducing today's
   signal, then the implementation until green. SBCL parity probes for every
   semantic above, each under `timeout`.

## Done when

- `(complex 1 2)`, `#C(1 2)`, and all SBCL contract cases above answer SBCL-equal
  values on the interpreter (print-compared).
- `./mvnw spring-javaformat:apply test` green (single foreground run, `timeout`,
  exit-code + surefire count check per AGENTS.md "Waiting for a Long Run").
- `.kb/` grep done for `complex|numeric-tower|number` topics before changing
  behavior; contradicting measurements written back into the matching `.kb` file.

## Timeout convention (all four items share this)

SBCL/`java -jar` probes always under `timeout 20`/`timeout 30`; `./mvnw` runs in
the foreground with an explicit `timeout` and the `tail -f --pid=$! /dev/null`
wait from AGENTS.md -- never detach-and-end-turn. A test failure that hangs is
killed by the timeout, not by the session clock.
