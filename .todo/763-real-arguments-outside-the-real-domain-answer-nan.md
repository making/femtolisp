# 763. `log`, `asin`, `acos` and `expt` of a real answer `NaN` where CL crosses into the plane

Difficulty: Medium

Filed 2026-09-10, from a conformance sweep of the CL number surface against SBCL 2.6.5 on the
same machine. `sqrt` of a negative real already crosses into the complex plane
(`(sqrt -1)` is `#C(0.0 1.0)` on all four backends); the rest of the family does not:

```
* (log -1d0)      => NaN          ; SBCL: #C(0.0d0 3.141592653589793d0)
* (asin 2d0)      => NaN          ; SBCL: #C(1.5707963267948966d0 -1.3169578969248166d0)
* (acos -4d0)     => NaN          ; SBCL: #C(3.141592653589793d0 -2.0634370688955608d0)
* (expt -8 1/3)   => NaN          ; SBCL: #C(1.0 1.7320508)
```

This is the "lite edge" `.todo/037-number-extensions.md` records after 754 landed ("`log` /
`expt` / `asin` of a negative/fractional REAL still answer NaN"). It is written down but not
filed, and it is the last thing between a program that computes over reals and the complex
tower that is otherwise complete: a real-valued expression that momentarily leaves the real
domain silently poisons everything downstream with `NaN` instead of answering the number CL
says it has.

`doc/*/guides/math-backends.md` currently states the NaN behaviour as the contract ("an
`asin`/`acos` argument outside `[-1, 1]` is `NaN` everywhere", "a negative base to a
fractional power is `NaN`"), so it is part of this change, not collateral.

RontoLisp has ONE float type, so the expected values below are written the way RontoLisp
prints a float -- no `d0` suffix.

## Test cases

`log` of a negative real:

| input | expected |
|---|---|
| `(log -1d0)` | `#C(0.0 3.141592653589793)` |
| `(log -100d0)` | `#C(4.605170185988092 3.141592653589793)` |
| `(log -1)` | `#C(0.0 3.141592653589793)` |
| `(log 0d0)` | `-Infinity` (unchanged -- the zero edge is not this todo) |
| `(log 1d0)` | `0.0` (unchanged) |

`asin` / `acos` outside `[-1, 1]`:

| input | expected |
|---|---|
| `(asin 2d0)` | `#C(1.5707963267948966 -1.3169578969248166)` |
| `(asin -2d0)` | `#C(-1.5707963267948966 1.3169578969248166)` |
| `(acos 2d0)` | `#C(0.0 1.3169578969248166)` |
| `(acos -2d0)` | `#C(3.141592653589793 -1.3169578969248166)` |
| `(acos -4d0)` | `#C(3.141592653589793 -2.0634370688955608)` |
| `(asin 2)` | `#C(1.5707963267948966 -1.3169578969248166)` |
| `(asin 0.5d0)` | `0.5235987755982989` (unchanged: still a real inside the domain) |
| `(acos 1d0)` | `0.0` (unchanged) |
| `(asin 1d0)` | `1.5707963267948966` (unchanged) |

`expt` of a negative base with a non-integer exponent:

| input | expected |
|---|---|
| `(expt -8d0 (/ 1d0 3d0))` | `#C(1.0000000000000002 1.7320508075688772)` |
| `(expt -8 1/3)` | `#C(1.0000000000000002 1.7320508075688772)` |
| `(expt -2d0 0.5d0)` | `#C(8.659560562354934e-17 1.4142135623730951)` |
| `(expt -2 2)` | `4` (unchanged: an integer exponent stays exact and real) |
| `(expt -8d0 2d0)` | `64.0` (unchanged: an integer-VALUED float exponent stays real) |
| `(expt 2 -1)` | `1/2` (unchanged) |

Note `(expt -2d0 0.5d0)` and `(sqrt -2d0)` disagree in the last bits (`sqrt` is
`#C(0.0 1.4142135623730951)`, exact zero real part) -- that is correct and matches SBCL;
`expt` goes through `exp(y * log x)` and `sqrt` does not. Do not "fix" one to the other.

The escape must be reachable from a variable, not only a literal, on the compiled backends:
`(let ((x -1d0)) (log x))` has to answer the complex too. That is the syntactic-steering
corner `.kb/jvm-complex.md` and `.kb/wasm-complex.md` both record, and it is what makes this
todo Medium rather than Easy -- the answer's TYPE now depends on a runtime value, so
`log`/`asin`/`acos`/`expt` become complex-capable call sites unconditionally, which turns the
JVM's `GROUP_COMPLEX` gate on for programs that never mention a complex. Deciding how the
gate handles that (widen the gate's trigger, or keep a real fast path with a complex-building
slow arm behind a branch) is the design question this todo carries; `.kb/jvm-complex.md`'s
byte-identity promise for complex-free programs is the constraint it must not break.

## What to do

1. Failing tests first (global rule): `LispEvaluatorTest`, then the JVM and WASM mirrors,
   including the `let`-carried cases.
2. Interpreter first, then decide the gate question above before touching either backend.
3. All four backends (`.kb/running-backends.md`). `--no-gc` has no complex representation, so
   it keeps NaN and must say so -- either a compile-time refusal like the one it gives for a
   `#C` literal, or a documented NaN carve-out.
4. `ci-spec.yaml` case; EN+JA reference pages for `log`, `asin`, `acos`, `expt`; the
   `math-backends.md` sentences quoted above; and `.todo/037-number-extensions.md`'s
   "Remaining lite edges" paragraph, which this closes.
5. Update `.kb/jvm-complex.md` and `.kb/wasm-complex.md` with whatever the gate decision was.

## Acceptance

Every case above green on all four backends; a program that mentions no complex still emits
byte-identically (the `.kb/jvm-complex.md` gate promise), or that promise is restated with
its new boundary in the same commit.

## Related

- `[[761-cis-asinh-acosh-atanh-are-not-defined]]` -- `(acosh 0d0)` and `(atanh 2d0)` are the
  same escape for two of the functions it adds
- `[[762-atan-and-log-take-only-one-argument]]` -- `(log -8d0 2d0)` needs both
- `[[764-complex-asin-and-acos-pick-the-wrong-branch-on-the-cut]]`
- `[[037-number-extensions]]`
