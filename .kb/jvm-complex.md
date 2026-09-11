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
  `_cu1` (the 15 unary math functions by int opcode: `asinh`, `acosh`, `atanh`
  have hand-rolled real arms -- `java.lang.Math` has no inverse hyperbolic --
  and `acosh`/`atanh` cross a real argument outside their domain into the
  complex arm at `(x, +0.0)`, the way `cis` always answers the arm),
  `_cconjugate`,
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

## The holder-presence probe (`.todo/757`, 2026-09-10)

The gate over-approximates: dead `sqrt` arms in an unpruned splice keep it
open, and callers the dispatchers keep alive defeat a reachability re-check,
so a gate-on class can still run where its `RontoComplex.class` file is
absent. Every holder TEST (the `numberp`/`complexp`/`realpart`/`imagpart`
inline shapes, the `_cmpb`/`_abs`/`_signum`/`_min`/`_max`/`_dbl` arms, the
printer dispatch, the `_eval` self-eval arm) therefore consults the `static
final boolean _hasComplex` probe first -- set once in `<clinit>` by a
`Class.forName` that catches `ClassNotFoundException` -- and takes its
holder-less shape when the class did not load. Exact, not heuristic: no
holder instance can exist without its class. Only the constructor paths (the
`_c*` helpers) keep hard links: building a complex without its class is a
genuinely missing file. Pinning test:
`JvmLispCompilerTest#aComplexFreeArangeProgramRunsStandaloneWithoutTheHolder`
(the lone-class run; the device-gated twin is
`JvmLinalgGpuAccelCompilerTest#aLazyResultAllocatesNoHostArrayOnTheCompiledBackend`).

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

## The asin/acos branch cut, and their exact real axis (`.todo/764`, 2026-09-11)

`asin` and `acos` cut the real axis outside `[-1, 1]`, and the value ON the cut
is the one CLHS names: continuous with quadrant IV above `+1`, quadrant II
below `-1`. **The side is decided by the sign of the REAL part; the sign of an
imaginary ZERO is discarded** -- `(asin #c(2d0 0d0))` and `(asin #c(2d0 -0d0))`
are ONE value, `#C(1.5707963267948966 -1.3169578969248166)`, SBCL's for both.
That is the opposite of `sqrt`, `log` and `acosh`, where the imaginary zero's
sign picks the sheet (`(sqrt #c(-1d0 -0d0))` is `#C(0.0 -1.0)`), and the split
is deliberate: SBCL and CLHS agree here, and a program that means one side of
an asin cut has to say which side anyway.

All three implementations (`Environment.complexAsin`/`complexAcos`, `_cu1`'s
`U1_ASIN`/`U1_ACOS`, `WasmComplexCompiler.emitComplexAsinInto`/`...Acos...`)
run Kahan's form over the two roots `u = sqrt(1 - z)` and `v = sqrt(1 + z)`:

```
asin z = (atan2(re, Re(u*v)),    asinh(Im(conj(u)*v)))
acos z = (2*atan2(Re(u), Re(v)), asinh(Im(conj(v)*u)))
```

Both properties fall out of it, which is why no arm special-cases the axis:

- **The cut**: the imaginary parts of `1 - z` and `1 + z` are computed as
  `0.0 - im` and `0.0 + im`, and IEEE makes BOTH `+0.0` for either signed zero,
  so `u` and `v` stay on one sheet whatever sign the argument's zero had.
- **The real axis**: a real argument inside `[-1, 1]` leaves both roots real,
  so asinh's argument is a difference of zeros and the imaginary part is
  EXACTLY `0.0`. Deriving acos as `pi/2 - asin z` would lose both -- it
  answered `#C(1.0471975511965976 -1.1102230246251565e-16)` for
  `(acos (complex 0.5d0 0d0))` where SBCL (and this) answer
  `#C(1.0471975511965979 0.0)`.
- **Accuracy**: `asin z` and `asinh(i*z)` now agree to the BIT, so
  `(asin #c(1 1))`'s imaginary part IS `(asinh #c(1 1))`'s real part and
  `(sin (asin #c(0d0 1d0)))` is exactly `#C(0.0 1.0)`. The
  `-i*log(i*z + sqrt(1 - z^2))` form this replaced was 2 ulp off there.

`asinh`'s real arm is the imaginary part of every one of those, so its grouping
is their accuracy: the large branch is ONE log over `|x| + hypot(|x|, 1)`, not
`log |x| + log(1 + hypot(1/|x|, 1))`, whose two roundings land a ulp high on 18%
of the arguments above 1 (measured 2026-09-11 against 60-digit BigDecimal; it is
what kept `(asin #c(-4d0 0d0))` a ulp off SBCL). Only the SUM can overflow, so
the huge rung is acosh's, at the same `8.5e307`; `(asinh 1d0)` and `(asinh 2d0)`
are unmoved by the regrouping.

Pinning tests: `LispEvaluatorTest#evalComplexAsinAcosOnTheBranchCut`,
`#evalComplexAsinAcosOfARealArgumentAnswerAnExactZero` and
`#evalComplexAsinAcosRoundTrip`, mirrored by
`JvmLispCompilerTest#compileAndRunComplexAsinAcos*` and
`WasmLispCompilerIntegrationTest#compileAndRunComplexAsinAcos*`; the
four-backend leg is `ci-spec.yaml`'s `complex-asin-acos-branch-cut`, which pins
the CONTRACT (one value for both zero signs, the cut's sign, the exact zeros)
rather than digits the backends round differently.

## Known corners (documented, not fixed here)

A complex arriving only through a variable beside a double literal takes the
unboxed path into `_dbl`'s `Expected number` landing (catchable, correctly
rendered) instead of the complex answer; ordering there answers `nil` instead
of signalling. The embedded runtime reader has no `#C` arm yet. `signum` of a
complex is 754's audit.

The `_cu1` real path and the interpreter's unary math are both `Math.<fn>`, so
they agree on every platform -- but a `Math` result is not one number:
`Math.exp(1.0)` is `2.718281828459045` on x64 and `2.7182818284590455` on
aarch64 (2026-09-10, the defect behind the deleted `.todo/756`, which was
`./mvnw test` red on every aarch64 box). The pinning tests therefore assert
the interpreter's own `Math` values (`Double.toString(Math.exp(1))`), never a
printed digit string, and only the platform-exact answers keep literals.

`Math.log` splits the same way, and the split reaches the COMPLEX answers
through `complexLog`'s `log(hypot(...))`: `Math.log(3.0)` is
`1.0986122886681098` on x64 (correctly rounded) and `1.0986122886681096` on
aarch64, so `(atanh 2)` answers `0.5493061443340549` / `...548` and
`(acosh #c(1 1))` `1.0612750619050357` / `...355` -- x64 landing on SBCL's
digits, aarch64 one ulp below them (2026-09-11, measured on the interpreter and
on `-o Probe.class` under `linux/amd64`; it was `./mvnw test` red on CI with the
literals, green on every aarch64 box). Only `Math.log` moves: the moduli it is
handed (`sqrt`/`hypot`) are the same doubles everywhere, so these pins spell the
call -- `"#C(" + Math.log(3.0) / 2 + " ...)"`,
`2 * Math.log(1.7000157758867898)` for the acosh sqrt-sum modulus -- and the
round trips (`(cosh (acosh #c(1 1)))`) assert closeness to the argument within
2 ulps, which is what a round trip actually pins.

Pinning tests: `JvmLispCompilerTest#compileAndRunComplex*` (mirrors
`LispEvaluatorTest`'s `evalComplex*` case for case);
`JvmRuntimeClassFilesTest` covers the holder's travelling list.
