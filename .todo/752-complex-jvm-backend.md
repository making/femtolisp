# 752. Complex numbers: JVM backend

Difficulty: High

Depends on 751 (the `LispComplex` value, its canonicalization, and the
interpreter semantics it pins). Spike filed 2026-09-09; SBCL contract and probe
log live in `.todo/751-complex-core-value-reader-interpreter.md`.

## Constraints found by the spike

- `runtime` imports NOTHING (not even `@Nullable`); its classes are COPIED into
  compiled output (`-o out.class`, `.jar`, Maven plugin `target/classes`). Any
  complex helper the emitted code needs must either be dependency-free (to live in
  `runtime`) or be emitted inline. A class added to `runtime` must join a
  travelling list (`JvmRuntimeClassFilesTest` fails otherwise;
  `.kb/jvm-export.md` "What travels").
- `am.ik.jvm` is language-independent and may not import rontolisp packages, so
  the complex representation there must be plain JVM values (`Object[]`, two
  `double`s, or similar), not `LispComplex`.
- `JvmLispCompiler.hasDoubleLiteral(args, ctx)` (JvmLispCompiler.java:4807) steers
  every arithmetic/comparison compiler (`JvmArithCompiler`, `JvmComparisonCompiler`,
  `JvmMinCompiler`, `JvmMaxCompiler`, `JvmAbsCompiler`, `JvmSignumCompiler`,
  `JvmRandomCompiler`, `JvmIntFusionCompiler`) onto a `double` fast path. A
  complex literal or a complex-typed operand must NOT take that path; extend the
  check (or add a `hasComplexOperand` gate) so complex flows to the object path.
- Precedents: `JvmQuoteCompiler.java:298` + `JvmEmitHelper.compileRatio` for how a
  non-trivial literal is materialized; `JvmNumericRuntimeBuilder` (note the
  `hasDoubleLiteral`-only-sees-literals comment at :1603) for runtime helpers.
- `complex` currently compiles via `expandComplexLite` (`JvmExprCompiler.java:1024`);
  rewire to the real implementation from 751. Rationals/bignums already have an
  exact-or-trap discipline on this path (`.kb/wasm-bignum.md` is the WASM twin;
  keep exact parts exact here too).

## Work

1. Choose the emitted representation (dedicated dependency-free holder in
   `runtime` vs. `Object[]` pair) and record WHY in `.kb/` (new or nearest
   numeric file) -- representation choices here are invisible until two sessions
   touch them at once (cf. AGENTS.md "Working Alongside Other Sessions").
2. `Jvm<Name>Compiler` + cases in `JvmExprCompiler.compileCons()` for:
   `complex`, `complexp`, `realp`, `realpart`, `imagpart`, `conjugate`, `phase`,
   and complex-capable `+ - * / abs sqrt expt = zerop` (AGENTS.md "Adding a
   Built-in Function" step 3). `rontolisp:`-package names (if any) go through the
   separate qualified if-chain, never the `cl:` switch.
3. Quote/print: complex literal emission + `#C(a b)` printer parity with the
   interpreter (print-compared in tests).
4. `JvmLispCompilerTest` cases mirroring 751's interpreter cases (same values,
   same errors); `BuiltinFunctionWrappers` entry per step 5 of the recipe (shared
   with 751 -- one side must own it; default owner is 751).

## Done when

- Every 751 interpreter case answers identically via
  `java -jar ... -o Prog.class && java Prog` (all four backends meet in 754's
  corpus cases; this item owns the JVM leg).
- `./mvnw spring-javaformat:apply test` green, single foreground run with
  `timeout`; `JvmRuntimeClassFilesTest` green if `runtime` gained a class.
