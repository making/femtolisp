# 753. Complex numbers: WASM backends (GC struct + scalar refusal)

Difficulty: High

Depends on 751 (the `LispComplex` value and pinned interpreter semantics).
Spike filed 2026-09-09; SBCL contract and probe log live in
`.todo/751-complex-core-value-reader-interpreter.md`.

## Constraints found by the spike

- `.kb/wasm-shared-coercion.md` INVARIANT: no site emits the numeric type ladder
  inline -- every numeric-to-`f64` need goes through the ONE shared coercion
  function. Complex needs real-PART extraction; add struct-aware arms to the
  shared function, do not inline a ladder at each arithmetic site. Non-number
  arms land in `_type_err_num` (catchable `Expected number, got: <prin1>` in EH
  mode; `.kb/error-handling.md:568`).
- Representation: WASM GC backend gets a struct type (two fields, one per real
  part), following the `TYPE_BIGINT`/ratio precedent (`.kb/wasm-bignum.md`: every
  boundary exact-or-trap; `_print_val`/`_princ_val`, `integerp`/`numberp`/
  `rationalp` all grew arms there -- `complexp`/`realp`/`realpart`/`imagpart` need
  the same treatment here).
- The no-GC scalar backend is for pure numeric exports
  (`.todo/037-number-extensions.md`: "no complex support (scalar backend is for
  pure numeric exports)"). Complex on the scalar path is a clean refusal (a
  compile-time/validation error naming the offending form), never a wrong number
  (cf. `.kb/array-literals.md:142` -- a wrong number is the failure mode to avoid).
- `complex` currently compiles via `expandComplexLite`
  (`WasmExprCompiler.java:1339`); rewire to the real implementation from 751.
- ` DoubleValuedForms` / int-fusion (`WasmIntFusionCompiler` analogues of the JVM
  `hasDoubleLiteral` steering) must treat complex operands as non-double, same as
  752's JVM gate.

## Work

1. GC struct definition + construction (`complex` builtin) + literal emission
   (quote path) + part extraction shared with the coercion function.
2. `Wasm<Name>Compiler` + cases in `WasmExprCompiler.compileCons()` for the same
   operator set as 752 (`WasmEmitHelper.castI31GetS()` to unbox, `ref.i31` to
   re-box per the AGENTS.md recipe step 4). `rontolisp:`-package names go through
   the separate qualified if-chain.
3. `_print_val`/`_princ_val` `#C(a b)` arm (printer parity with 751).
4. Scalar-backend refusal with a naming-the-form error; pin with a test that the
   refusal fires (not a trap, not a wrong number).
5. `WasmLispCompilerIntegrationTest` cases mirroring 751 (Preview 1 WASM; plus a
   `--component` leg where the harness supports it -- 754's corpus cases cover
   the component leg end to end).

## Done when

- Every 751 interpreter case answers identically via
  `wasmtime run test.wasm` (Preview 1) and, where applicable, the component leg.
- Scalar-backend complex program refused with a readable error (test-pinned).
- `./mvnw spring-javaformat:apply test` green, single foreground run with
  `timeout`.
