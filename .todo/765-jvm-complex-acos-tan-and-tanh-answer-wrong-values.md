# 765. The JVM backend's complex `acos`, `tan` and `tanh` answer wrong values

Difficulty: Medium

Filed 2026-09-10, from a cross-backend sweep of the complex transcendentals. Three of the
fifteen unary functions in `JvmComplexRuntimeBuilder`'s `_cu1` answered numbers that are not
close to the interpreter's -- not a last-digit divergence, a different value. The JVM is
supposed to mirror the interpreter case for case (`.kb/jvm-complex.md`), and
`JvmLispCompilerTest#compileAndRunComplex*` did not cover these three shapes.

## Re-measured 2026-09-11, `linux/amd64`: `acos` is already fixed

`.todo/764` landed in between and replaced the `U1_ACOS` body wholesale with the Kahan
form, which removed the slot swap this item diagnosed AND moved the interpreter's own
`acos` values. **The table this item was filed with is stale on every `acos` row** -- both
columns of it. Re-measured, all fifteen arms at seven points (`#c(1 1)`, `#c(-1.5 0.25)`,
`#c(0.5 -2)`, the two axes `#c(0 1)`/`#c(1 0)`, the two reals off the cut `#c(-4 0)`/`#c(2 0)`),
compiled with `-o Q.class` and compared to `java -jar` line for line:

- `exp log sin cos asin acos atan sinh cosh asinh acosh atanh cis` -- byte-identical,
  every point. Nothing left to fix, `acos` included.
- `tan` and `tanh` -- different on all seven points each. The whole remaining hole.

RontoLisp has ONE float type, so the values below are written the way RontoLisp prints a
float -- no `d0` suffix. Reproduce with
`java -jar target/rontolisp-0.1.0-SNAPSHOT-exec.jar p.lisp -o P.class --class-name P && java P`.

## The red that is left

| input | interpreter (correct) | JVM today |
|---|---|---|
| `(tan #c(1d0 1d0))` | `#C(0.2717525853195118 1.0839233273386943)` | `#C(0.9231406713107814 1.4024612103199083)` |
| `(tan #c(0d0 1d0))` | `#C(0.0 0.7615941559557649)` | `#C(0.0 1.1752011936438014)` |
| `(tan #c(1d0 0d0))` | `#C(1.557407724654902 0.0)` | `#C(0.8414709848078965 0.0)` |
| `(tan #c(-1.5d0 0.25d0))` | `#C(-1.025332061229339 3.786108936814774)` | `#C(-0.9633998992619 3.7850332761844503)` |
| `(tan #c(0.5d0 -2d0))` | `#C(0.03021598732287752 -0.9799408499617382)` | `#C(1.406228080601989 -3.408107722893662)` |
| `(tan #c(-4d0 0d0))` | `#C(-1.1578212823495775 0.0)` | `#C(0.7568024953079282 0.0)` |
| `(tan #c(2d0 0d0))` | `#C(-2.185039863261519 0.0)` | `#C(0.9092974268256817 0.0)` |
| `(tanh #c(1d0 1d0))` | `#C(1.0839233273386943 0.2717525853195118)` | `#C(1.4024612103199083 0.9231406713107814)` |
| `(tanh #c(0d0 1d0))` | `#C(0.0 1.557407724654902)` | `#C(0.0 0.8414709848078965)` |
| `(tanh #c(1d0 0d0))` | `#C(0.7615941559557649 0.0)` | `#C(1.1752011936438014 0.0)` |
| `(tanh #c(-1.5d0 0.25d0))` | `#C(-0.915271913261314 0.04380217692516715)` | `#C(-2.1191079347322153 0.38340378508125217)` |
| `(tanh #c(0.5d0 -2d0))` | `#C(1.3212865837711916 0.8508781211449374)` | `#C(0.875616402372163 -1.256395103674885)` |
| `(tanh #c(-4d0 0d0))` | `#C(-0.9993292997390669 0.0)` | `#C(-27.289917197127746 0.0)` |
| `(tanh #c(2d0 0d0))` | `#C(0.9640275800758169 0.0)` | `#C(3.626860407847019 0.0)` |

The interpreter agrees with SBCL 2.6.5 on every row above to within a couple of ulp.

## The cause

`U1_TAN`/`U1_TANH` clobber the denominator's real part. The shared body stores
`s.re`->6, `s.im`->8, `c.re`->14, `c.im`->2, then overwrites slot 14 with `|c|^2`
(`14*14 + 2*2`). The quotient that follows still reads slot 14 where it means `c.re`:
it computes `(s.re*|c|^2 + s.im*c.im)/|c|^2` and `(s.im*|c|^2 - s.re*c.im)/|c|^2` instead of
`(s.re*c.re + s.im*c.im)/|c|^2` and `(s.im*c.re - s.re*c.im)/|c|^2`. That is why the pure
cases degenerate to the NUMERATOR: for `#c(0 1)` the `c.im` term is zero and the `|c|^2`
factors cancel, leaving `sinh(1)` where `tanh(1)` belongs. `|c|^2` needs a slot of its own
(10 and 12 are free in this arm).

The `U1_ACOS` half of the original diagnosis (slots 10 and 12 swapped, imaginary sign
inverted, out of `pi/2 - asin z`) no longer exists: `.todo/764` replaced the derivation.

## What to do

1. Failing test first (global rule): the census above, as a JVM-vs-interpreter pin rather
   than literal expected strings -- the point of the mirror is that the two agree, and a
   literal would only capture the platform's `Math` rounding.
2. Give `|c|^2` its own slot. The audit step this item asked for is done: the census in
   step 1 IS the audit, and it clears the other thirteen arms.
3. Re-run all four backends (`.kb/running-backends.md`) -- WASM has its own, different,
   break in this family (`[[766-wasm-phase-is-wrong-when-the-real-part-is-a-zero]]`).
4. `.kb/jvm-complex.md` gains a section saying the fifteen `_cu1` arms are pinned
   individually against the interpreter, so a slot reuse cannot go unnoticed again.

## Acceptance

Every row above identical between `java -jar ... p.lisp` and the compiled `java P`;
`ci-spec.yaml` grown a complex-transcendental case so the E2E lane covers the family;
existing `compileAndRunComplex*` cases unchanged.

## Related

- `[[766-wasm-phase-is-wrong-when-the-real-part-is-a-zero]]` -- the same sweep, the other backend
- `[[764-complex-asin-and-acos-pick-the-wrong-branch-on-the-cut]]` -- landed first, and took
  the `acos` half of this item with it
- `[[037-number-extensions]]`
