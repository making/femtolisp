> **Update 2026-07-05 (parse-number e2e):** `/=` shipped (pairwise-different
> expansion over `=`, variadic), plus lite `complex` (zero imaginary part
> only), float-type + computed-type `coerce`, and the predefined
> `*read-default-float-format*` (informational; every float is double).

# Number system extensions (`rational`, `rationalize`, `complex` numbers, `realp`, `complexp`, `realpart`, `imagpart`, `phase`, `conjugate`, `integer-decode-float`, `scale-float`, `float-radix`, `decode-universal-time`, `encode-universal-time`)

**Status:** partially implemented — the lite `complex`, float-type `coerce`,
`/=` and `*read-default-float-format*` shipped 2026-07-05 (see the update
above). The rest is low priority: full complex numbers and time decomposition
are niche.

## What's missing

RontoLisp has the core numeric tower: integers (with `BigInteger` bignum), floats, and rationals (`LispRatio` via `BigInteger` numerator/denominator). Arithmetic (`+`, `-`, `*`, `/`), comparison (`=`, `<`, `>`, `<=`, `>=`), rounding (`truncate`, `floor`, `ceiling`, `round`), modular (`mod`, `rem`), absolute (`abs`), root (`sqrt`, `isqrt`), power (`expt`, `exp`, `log`), trigonometric (`sin`, `cos`, `tan`, `asin`, `acos`, `atan`, `sinh`, `cosh`, `tanh`), number theory (`gcd`, `lcm`), sign (`signum`), and predicates (`numberp`, `integerp`, `floatp`, `rationalp`, `zerop`, `plusp`, `minusp`, `evenp`, `oddp`).

### Missing numeric functions

| Function | Purpose | Difficulty |
|----------|---------|------------|
| `rational` | Exact rational from float: `(rational 1.5)` -> `3/2` | Easy |
| `rationalize` | Simplest rational within tolerance: `(rationalize 1.4999999 0.01)` -> `3/2` | Medium |
| `realp` | True for real numbers (always t without complex) | Trivial |
| `complexp` | Complex number predicate | — (no complex type) |
| `realpart` / `imagpart` | Complex accessors | — (no complex type) |
| `conjugate` | Complex conjugate | — (no complex type) |
| `phase` | Complex phase angle | — (no complex type) |
| `integer-decode-float` | Decode float into significand, base, exponent | Easy |
| `scale-float` | Scale float by power of radix | Easy |
| `float-radix` | Radix of float type (always 2) | Trivial |
| `float-digits` | Significand digits of a float | Trivial |
| `float-sign` | Sign of a float, as a float | Trivial |
| `most-positive-double-float` | Largest representable double | Trivial |
| `most-negative-double-float` | Most negative representable double | Trivial |

### Missing time decomposition

`get-universal-time` already exists (see `.kb/time-environment-builtins.md`);
only the decomposition/composition pair is missing.

| Function | Purpose | Difficulty |
|----------|---------|------------|
| `decode-universal-time` | Break down universal time | Medium |
| `encode-universal-time` | Build universal time | Medium |

### Complex numbers

> **Update 2026-09-09 (.todo/754 landed):** the four-step split is complete --
> type system, corpus and docs are in (see the step list below). Remaining
> lite edges, all documented where they occur: `log`/`expt`/`asin` of a
> negative/fractional REAL still answer NaN (only complex operands and `sqrt`
> of a negative real cross into the plane); `(integer 0 10)` upgrades to
> `integer` where SBCL answers `(mod 11)`; variable-carried complex arithmetic
> on the compiled backends steers syntactically (see `.todo/755` for the one
> place that steering goes wrong today).

CL has a full complex number tower. RontoLisp implements it in four steps:
- 751 (done): `LispComplex` (real + imaginary parts) + reader/printer +
  interpreter arithmetic, predicates and accessors.
- 752 (done): JVM backend -- the `RontoComplex` holder, the gated `_c*` helper
  group (`.kb/jvm-complex.md`), every 751 interpreter case answering
  identically via `java Prog`.
- 753 (done): WASM GC -- the tagged `TYPE_COMPLEX` struct, the `_c*` runtime
  group plus call-site compilers (`.kb/wasm-complex.md`), every 751
  interpreter case answering identically via `wasmtime run test.wasm` (plus a
  `--component` smoke leg); the `--no-gc` scalar backend refuses complex
  construction and operators at compile time.
- 754 (done 2026-09-09): type system, corpus, docs. `typep`/`typecase`/
  `etypecase`/`check-type` arms for `complex` (incl. `(complex part-type)`)
  and the `real`-vs-`number` split (`(typep #c(1 2) 'real)` is NIL now);
  `subtypep` lattice (`complex` under `number`, `(complex x)` part-wise);
  `coerce` to/from `complex`/`real` (incl. `(complex part-type)` and computed
  designators); `upgraded-complex-part-type` (expansion-only, so all four
  backends share it);   `type-of` answering atomic `COMPLEX` (the numeric
  convention here is atomic, unlike SBCL's bounded specifier) with
  `class-of`/`find-class` following via the new built-in class.
  `signum` of a complex answers the
  unit vector on all four backends (JVM `_csignum`, WASM `_csignum`);
  `float`/`floor`-family/`numerator`/`denominator` over a complex signal the
  catchable "Expected real number" on all four (the funnel arms are one
  message now); `isqrt`/`mod`/`rem`/`gcd`/`lcm`/bitwise stay
  "Expected integer". `ci-spec.yaml` pins the contract (three cases; `+ - *`
  stay out -- `.todo/755`); per-operator EN+JA pages for the eight names.

### Implementation approach (pragmatic subset)

1. `rational` — convert float to exact ratio (Easy, useful).
2. `realp` — always true for existing types (Trivial).
3. `float-radix`, `float-digits`, `most-positive-double-float`, `most-negative-double-float` — constants (Trivial).
4. `integer-decode-float`, `scale-float`, `float-sign` — IEEE 754 bit manipulation (Easy).
5. Complex numbers — defer until there's a concrete use case.
6. Time decomposition — `decode-universal-time` is useful but requires timezone handling.

### Related

- `[[032-multiple-value-system]]` (`integer-decode-float`, `decode-universal-time` return multiple values)
- `[[035-type-system]]` (`coerce` between number types)
