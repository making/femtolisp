# 762. `atan` and `log` take only one argument -- no `atan2`, no logarithm base

Difficulty: Medium

Filed 2026-09-10, from a conformance sweep of the CL number surface against SBCL 2.6.5 on the
same machine. Both functions are registered as strictly unary
(`Environment.defineUnaryComplex(env, LispNames.ATAN, ...)` and `log`'s twin), so the ANSI
optional second argument is an arity error:

```
* (atan 1d0 1d0)
Unhandled condition: ATAN expects 1 arguments, got 2
* (log 8 2)
Unhandled condition: LOG expects 1 arguments, got 2
```

`atan y x` is C's `atan2`: the angle of the vector `(x, y)`, i.e. the phase of the complex
number `x + yi`, over the full circle. `log number base` is `(/ (log number) (log base))`.
Neither has a workaround a caller can write portably today -- `(phase (complex x y))` is the
closest, and it is not what a program reaching for `atan2` writes.

RontoLisp has ONE float type, so the expected values below are written the way RontoLisp
prints a float -- no `d0` suffix, and the integer-argument cases answer at double precision
where SBCL answers at single.

## `atan y x`

The signed zeros matter: `atan2` is exactly where `-0.0` earns its keep, and the interpreter
and the JVM already agree with `Math.atan2` through the complex `phase`, so the real
two-argument form should reuse that path rather than grow a second quadrant assembly. On WASM
that path is currently WRONG for a zero real part --
`[[766-wasm-phase-is-wrong-when-the-real-part-is-a-zero]]` must land first or the four
`x == 0` rows below will be wrong on two of the four backends.

| input | expected |
|---|---|
| `(atan 1d0 1d0)` | `0.7853981633974483` |
| `(atan 0d0 1d0)` | `0.0` |
| `(atan 1d0 0d0)` | `1.5707963267948966` |
| `(atan 1d0 -1d0)` | `2.356194490192345` |
| `(atan 0d0 -1d0)` | `3.141592653589793` |
| `(atan -0d0 -1d0)` | `-3.141592653589793` |
| `(atan -1d0 1d0)` | `-0.7853981633974483` |
| `(atan -1d0 0d0)` | `-1.5707963267948966` |
| `(atan -1d0 -1d0)` | `-2.356194490192345` |
| `(atan 0d0 0d0)` | `0.0` |
| `(atan -0d0 0d0)` | `-0.0` |
| `(atan 1 1)` | `0.7853981633974483` |
| `(atan 3 4)` | `0.6435011087932844` |

A complex argument is an error in the two-argument form (CLHS: both arguments must be real):
`(atan #c(1d0 1d0) 1d0)` should signal the catchable "Expected real number" the ordering
funnel already answers, not compute something.

The identity worth a test of its own, since it ties the new form to a surface that already
works: `(atan (imagpart z) (realpart z))` equals `(phase z)` for every `z` in the table of
`[[766-wasm-phase-is-wrong-when-the-real-part-is-a-zero]]`.

## `log number base`

| input | expected |
|---|---|
| `(log 8 2)` | `3.0` |
| `(log 100 10)` | `2.0` |
| `(log 1024 2)` | `10.0` |
| `(log 8d0 2d0)` | `3.0` |
| `(log 1000d0 10d0)` | `2.9999999999999996` |

The exact-power cases (`(log 8 2)` = `3.0`, `(log 1024 2)` = `10.0`) come out exact from the
plain quotient of two `Math.log` calls on the interpreter and the JVM; do not add a
special case for them, and do NOT pin them exact on WASM, whose software `log` carries its
documented error.

A negative `number` crosses into the plane, which is
`[[763-real-arguments-outside-the-real-domain-answer-nan]]`'s job; once that lands,
`(log -8d0 2d0)` is `#C(3.0 4.532360141827194)`. A complex `number` or `base` is legal in CL
and is just the quotient of two complex logs: `(log #c(1d0 1d0) 2d0)` is
`#C(0.5 1.1330900354567985)` and `(log #c(1d0 1d0) #c(2d0 1d0))` is
`#C(0.7455202635908202 0.5464509967419067)`.

## What to do

1. Failing tests first (global rule): `LispEvaluatorTest`, then the JVM and WASM mirrors.
2. Widen both registrations to `1..2` arity. The one-argument path must stay
   byte-identical -- it is the hot one, and every backend's unary fast path keys off it.
3. On the JVM, `atan/2` is `Math.atan2` and `log/2` is one extra `DDIV`; on WASM they reuse
   the existing `emitAtan2Into` and software `log` cores rather than growing new ones.
4. All four backends (`.kb/running-backends.md`), a `ci-spec.yaml` case, and the EN+JA
   reference pages for `atan` and `log` (both currently document one argument).

## Acceptance

Every case above green on all four backends; the unary `atan`/`log` output unchanged
everywhere (diff the emitted class and module for a program that uses only the unary form).

## Related

- `[[766-wasm-phase-is-wrong-when-the-real-part-is-a-zero]]` -- blocks the `x == 0` rows
- `[[763-real-arguments-outside-the-real-domain-answer-nan]]` -- `(log -8d0 2d0)`
- `[[761-cis-asinh-acosh-atanh-are-not-defined]]`
- `[[037-number-extensions]]`
