# JVM complex numbers

The JVM representation of a complex value, and the gates that keep
complex-free programs byte-identical. Interpreter semantics (canonicalization,
exactness, SBCL parity) live in `.todo/751-*`; the type-system/corpus/docs leg
is `.todo/754-*`; the WASM twin is `.todo/753-*`.

## Representation: `runtime/RontoComplex`, not `Object[]`

A complex value is a `RontoComplex` holder (two `Object` fields holding the
compiled real representations: `Long`, `BigInteger`, `BigInteger[2]`,
`Double`). An `Object[2]` pair was rejected because it is structurally
identical to a cons cell and would answer `consp`/`car`/every cons-shaped
predicate wrongly; an `instanceof` keeps all of those answering with zero
exclusion arms. The holder is dumb (fields plus part-wise `equals`/`hashCode`
over the compiled shapes, which is the interpreter's `eql`); all logic lives
in generated helpers so nothing duplicates `_rat`/`_norm`/`_dbl`.

## Two helper tiers

- Unconditional numeric helpers gain holder arms that are emitted only for a
  complex-capable program: `_abs` (float modulus), `_cmpb` (part-wise numeric:
  `=` and `zerop` over holders compare, ordering never reaches it),
  `_min`/`_max` (throw `Expected real number`). `eql`/`equal`/`eq` need
  nothing (they fall through to the holder's `equals`).
- Gated `GROUP_COMPLEX` (`JvmComplexRuntimeBuilder`, only when
  `mayCreateComplex`: a `#C` literal, a `complex`/`conjugate` call, a `sqrt`
  mention, or a `#'complex`/`#'conjugate`/`#'phase` designator): `_ccomplex`
  (canonicalize), `_cadd`/`_csub`/`_cmul`/`_cdiv` (exact-or-float over
  `_add`/`_sub`/`_mul`/`_div`/`_dbl`/`_cmp`, so funnels match real
  arithmetic), `_cneg` (separate from `_csub`-from-zero: `0.0 - 0.0` is
  `+0.0`, `-0.0` is not), `_csqrt` (negatives root into the plane),
  `_cpow` (exact integer powers by squaring, else `exp(w*log z)`),
  `_cu1` (the 11 unary math functions by int opcode), `_cconjugate`,
  `_ccmpb` (throw-on-holder then delegate), `_cphase`, and the `#C(re im)`
  printer arms (parts recurse through the same renderer).
  The holder class file travels exactly then (`needsComplexRuntime`).

The hard rule: no `am/ik/rontolisp/runtime/RontoComplex` reference -- class
constant, field, `instanceof`, helper body -- exists in a class whose gate is
off. The constant pool is walked by name (`projectReferences`), so even an
unused entry breaks the travelling closure; and `instanceof` resolves its
class on first execution, so a stray test would `NoClassDefFoundError` an
otherwise single-file artifact the first time it prints. References are
created only under the gate (nullable holder refs, `ctx.usesComplex`
emissions, nullable print refs); helper calls use on-demand refs plus the
force-on retry.

## Call-site rules (`JvmComplexCompiler`, per-op gates)

- `complex`/`conjugate`/`sqrt`/`phase` always call their helper (`sqrt`
  unconditionally: negativity is a runtime property, and `NaN`-vs-plane is a
  wrong number, not an error; `phase`/`conjugate` the same way, so first-class
  references work through their wrappers). `complexp` is constant nil,
  `realp`/`realpart`/`imagpart`/`numberp` take the holder-less shape when the
  program cannot build a holder -- no holder can exist then, and the class
  stays out of the constant pool either way. Literals (code position and
  `quote`) emit parts plus `_ccomplex`.
- `hasComplexOperand` (a literal or `complex`/`conjugate` form in the tree)
  steers `+ - * /` to the `_c*` fold, `abs`/`expt`/unary-math off the double
  path, and `=`/`zerop`/`1+`/`1-` (which expand to them) with it; ordering
  uses `_ccmpb`. `min`/`max` need no gate (`isDefinitelyDouble` never fires
  on complex). Int-fusion, typed loops and raw stores decline a tree
  containing complex (the fused bail would answer `_add`'s error, not the
  complex value).
- `#'complex`/`#'conjugate`/`#'sqrt`/`#'phase` wrappers are reference-gated on
  the JVM (their bodies call gated helpers; an ungated wrapper would force the
  group into every program). `fboundp` still answers from the static registry.

## Known corners (documented, not fixed here)

A complex arriving only through a variable beside a double literal takes the
unboxed path into `_dbl`'s `Expected number` landing (catchable, correctly
rendered) instead of the complex answer; ordering there answers `nil` instead
of signalling. The embedded runtime reader has no `#C` arm yet. `signum` of a
complex is 754's audit.

Pinning tests: `JvmLispCompilerTest#compileAndRunComplex*` (mirrors
`LispEvaluatorTest`'s `evalComplex*` case for case);
`JvmRuntimeClassFilesTest` covers the holder's travelling list.
