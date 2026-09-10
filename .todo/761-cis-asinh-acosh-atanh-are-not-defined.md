# 761. `cis`, `asinh`, `acosh` and `atanh` are not defined

Difficulty: Medium

Filed 2026-09-10, from a conformance sweep of the CL complex-number surface against
SBCL 2.6.5 on the same machine. Four ANSI functions are simply absent:

```
* (cis 0d0)
Unhandled condition: The function CIS is undefined
* (asinh 1d0)
Unhandled condition: The function ASINH is undefined
* (acosh 2d0)
Unhandled condition: The function ACOSH is undefined
* (atanh 0.5d0)
Unhandled condition: The function ATANH is undefined
```

They are absent on every backend -- the interpreter's `Environment` never defines them, so
nothing downstream can. `sinh`/`cosh`/`tanh` and `asin`/`acos`/`atan` are all present, so the
inverse hyperbolic trio is the only hole left in the transcendental family, and `cis` is the
only missing constructor in the polar-form family (`abs`/`phase`/`signum`/`exp`/`log` are all
there).

RontoLisp has ONE float type, so the expected values below are written the way RontoLisp
prints a float -- no `d0` suffix. Everything is `Math`-accurate on the interpreter and the
JVM; WASM's software cores carry their documented error (`.kb/wasm-complex.md`,
"Transcendentals", and `doc/*/guides/math-backends.md`), so pin those with `isCloseTo`.

## Definitions

```
cis x     = (complex (cos x) (sin x))
asinh z   = log(z + sqrt(z^2 + 1))
acosh z   = 2 * log(sqrt((z + 1) / 2) + sqrt((z - 1) / 2))     ; the ANSI form
atanh z   = (log(1 + z) - log(1 - z)) / 2
```

`acosh`'s ANSI form is the one to implement: the naive `log(z + sqrt(z^2 - 1))` puts the
branch cut in the wrong place. A real argument outside the function's real domain has to
cross into the plane -- see the `(acosh 0d0)` and `(atanh 2d0)` cases below, which are the
same escape `[[763-real-arguments-outside-the-real-domain-answer-nan]]` covers for
`log`/`asin`/`acos`; land the two together or 763 first.

## Test cases

`cis` -- unit modulus, the given argument as its phase:

| input | expected |
|---|---|
| `(cis 0d0)` | `#C(1.0 0.0)` |
| `(cis 1d0)` | `#C(0.5403023058681398 0.8414709848078965)` |
| `(cis (/ pi 4))` | `#C(0.7071067811865476 0.7071067811865475)` |
| `(cis pi)` | `#C(-1.0 1.2246467991473532e-16)` |
| `(cis (- (/ pi 2)))` | `#C(6.123233995736766e-17 -1.0)` |
| `(abs (cis 1d0))` | `1.0` |
| `(phase (cis 1d0))` | `1.0` |

Real `asinh` / `acosh` / `atanh`, and the round trips that pin them against the forward
functions already present:

| input | expected |
|---|---|
| `(asinh -2d0)` | `-1.4436354751788103` |
| `(asinh -1d0)` | `-0.881373587019543` |
| `(asinh 0d0)` | `0.0` |
| `(asinh 1d0)` | `0.881373587019543` |
| `(asinh 2d0)` | `1.4436354751788103` |
| `(acosh 1d0)` | `0.0` |
| `(acosh 1.5d0)` | `0.9624236501192069` |
| `(acosh 2d0)` | `1.3169578969248166` |
| `(acosh 2.5d0)` | `1.566799236972411` |
| `(acosh 3d0)` | `1.762747174039086` |
| `(atanh -0.5d0)` | `-0.5493061443340549` |
| `(atanh 0d0)` | `0.0` |
| `(atanh 0.5d0)` | `0.5493061443340549` |
| `(sinh (asinh 2d0))` | `2.0` |
| `(cosh (acosh 3d0))` | `3.0` |
| `(tanh (atanh 0.5d0))` | `0.5` |

Real arguments off the real domain -- these must answer a complex, not `NaN`:

| input | expected |
|---|---|
| `(acosh 0d0)` | `#C(0.0 1.5707963267948966)` |
| `(atanh 2d0)` | `#C(0.5493061443340549 1.5707963267948966)` |

Complex arguments, including the branch cuts (`asinh` cuts the imaginary axis outside
`[-i, i]`, `acosh` the real axis left of 1, `atanh` the real axis outside `[-1, 1]`):

| input | expected |
|---|---|
| `(asinh #c(1d0 1d0))` | `#C(1.0612750619050357 0.6662394324925153)` |
| `(asinh #c(0d0 2d0))` | `#C(1.3169578969248166 1.5707963267948966)` |
| `(asinh #c(0d0 -4d0))` | `#C(-2.0634370688955608 -1.5707963267948966)` |
| `(sinh (asinh #c(1d0 1d0)))` | `#C(1.0 1.0000000000000002)` (`#c(1 1)` to within a few ulp) |
| `(acosh #c(1d0 1d0))` | `#C(1.0612750619050357 0.9045568943023813)` |
| `(acosh #c(0d0 0d0))` | `#C(0.0 1.5707963267948966)` |
| `(acosh #c(0d0 -0d0))` | `#C(0.0 -1.5707963267948966)` |
| `(acosh #c(-4d0 0d0))` | `#C(2.0634370688955608 3.141592653589793)` |
| `(cosh (acosh #c(1d0 1d0)))` | `#C(1.0000000000000002 1.0)` (`#c(1 1)` to within a few ulp) |
| `(atanh #c(1d0 1d0))` | `#C(0.40235947810852507 1.0172219678978514)` |
| `(atanh #c(2d0 0d0))` | `#C(0.5493061443340549 1.5707963267948966)` |
| `(atanh #c(2d0 -0d0))` | `#C(0.5493061443340549 -1.5707963267948966)` |
| `(atanh #c(-4d0 0d0))` | `#C(-0.2554128118829953 1.5707963267948966)` |
| `(atanh #c(-4d0 -0d0))` | `#C(-0.2554128118829953 -1.5707963267948966)` |
| `(tanh (atanh #c(1d0 1d0)))` | `#C(0.9999999999999998 0.9999999999999999)` (`#c(1 1)` to within a few ulp) |

One case is deliberately NOT pinned to SBCL: `(acosh #c(-4d0 -0d0))` answers
`#C(-2.0634370688955608 -3.141592653589793)` there, a NEGATIVE real part, while CLHS gives
`acosh`'s range as real part >= 0 (`#C(2.0634370688955608 -3.141592653589793)`). Measure what
the ANSI formula produces and pin THAT, with a comment saying SBCL differs.

## What to do

1. Failing tests first (global rule), one per surface: `LispEvaluatorTest` for the
   interpreter, then `JvmLispCompilerTest` and `WasmLispCompilerIntegrationTest` mirroring
   them case for case, the way the complex families already do.
2. Add the four names per `.kb/adding-primitives.md` -- both dispatch chains and both macro
   registries. `cis` is a two-line expansion over `cos`/`sin`; the inverse hyperbolics are
   real cores plus complex arms beside the existing `asin`/`acos`/`atan` ones.
3. All four backends (`.kb/running-backends.md`): interpreter, JVM, WASM preview 1, WASM
   component. `--no-gc` refuses complex already (`.kb/wasm-complex.md`), so its real-only
   arms are what it gets -- or an explicit refusal, matching what it does for `asin`.
4. `ci-spec.yaml` case, EN+JA reference pages for the four names, and the
   `doc/*/guides/math-backends.md` paragraph they belong in (it currently claims "every
   transcendental built-in now works on all three backends").
5. Update `.kb/jvm-complex.md` and `.kb/wasm-complex.md`: both say "the eleven unary math
   functions", which becomes fourteen.

## Acceptance

Every case above green on all four backends; the `--simd` legs of `CiSpecE2eTest` green;
`.todo/037-number-extensions.md`'s complex section updated to say the transcendental family
is complete.

## Related

- `[[763-real-arguments-outside-the-real-domain-answer-nan]]` -- `(acosh 0d0)` / `(atanh 2d0)`
- `[[762-atan-and-log-take-only-one-argument]]`
- `[[037-number-extensions]]`
