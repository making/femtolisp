# 765. The JVM backend's complex `acos`, `tan` and `tanh` answer wrong values

Difficulty: Medium

Filed 2026-09-10, from a cross-backend sweep of the complex transcendentals. Three of the
eleven unary functions in `JvmComplexRuntimeBuilder`'s `_cu1` answer numbers that are not
close to the interpreter's -- not a last-digit divergence, a different value. The JVM is
supposed to mirror the interpreter case for case (`.kb/jvm-complex.md`), and
`JvmLispCompilerTest#compileAndRunComplex*` did not cover these three shapes.

RontoLisp has ONE float type, so the values below are written the way RontoLisp prints a
float -- no `d0` suffix. Reproduce with
`java -jar target/rontolisp-0.1.0-SNAPSHOT-exec.jar p.lisp -o P.class --class-name P && java P`.

## The red

| input | interpreter (correct) | JVM today |
|---|---|---|
| `(acos #c(0d0 1d0))` | `#C(1.5707963267948966 -0.8813735870195428)` | `#C(2.4521699138144393 -0.0)` |
| `(acos #c(1d0 1d0))` | `#C(0.9045568943023813 -1.0612750619050355)` | `#C(2.632071388699932 -0.6662394324925153)` |
| `(acos #c(0.5d0 0d0))` | `#C(1.0471975511965976 -1.1102230246251565e-16)` | `#C(1.5707963267948966 -0.5235987755982989)` |
| `(acos #c(2d0 0d0))` | `#C(0.0 -1.3169578969248164)` | `#C(2.887754223719713 -1.5707963267948966)` |
| `(acos #c(-4d0 0d0))` | `#C(3.141592653589793 -2.0634370688955617)` | `#C(3.6342333956904582 1.5707963267948966)` |
| `(tan #c(1d0 1d0))` | `#C(0.2717525853195118 1.0839233273386943)` | `#C(0.9231406713107814 1.4024612103199083)` |
| `(tan #c(0d0 1d0))` | `#C(0.0 0.7615941559557649)` | `#C(0.0 1.1752011936438014)` |
| `(tan #c(1d0 0d0))` | `#C(1.557407724654902 0.0)` | `#C(0.8414709848078965 0.0)` |
| `(tanh #c(1d0 1d0))` | `#C(1.0839233273386943 0.2717525853195118)` | `#C(1.4024612103199083 0.9231406713107814)` |
| `(tanh #c(0d0 1d0))` | `#C(0.0 1.557407724654902)` | `#C(0.0 0.8414709848078965)` |
| `(tanh #c(1d0 0d0))` | `#C(0.7615941559557649 0.0)` | `#C(1.1752011936438014 0.0)` |

The interpreter agrees with SBCL 2.6.5 on every row above to within a couple of ulp (the
`acos` rows carry the small asin residue `[[764-complex-asin-and-acos-pick-the-wrong-branch-on-the-cut]]`
covers; that is a separate, much smaller, defect).

`asin` is correct on the JVM, and `sin`/`cos`/`sinh`/`cosh`/`exp`/`log`/`sqrt`/`expt`/`atan`
all agree with the interpreter -- these three are the whole hole.

## The cause, both of them found

Both are in `JvmComplexRuntimeBuilder`'s `_cu1` body.

**`U1_ACOS` reads the wrong two slots.** `emitAsinInto(c, refs, cp, 2, 4, 10, 12, ...)` leaves
`w = log(iz + sqrt(1 - z^2))` in slots 10 (real) and 12 (imaginary), and `U1_ASIN` correctly
answers `-i*w = (im(w), -re(w))` -- it pushes slot 12 then `-`slot 10. `U1_ACOS` wants
`pi/2 - asin(z)`, which is `(pi/2 - im(w), re(w))`, but pushes `pi/2 - `slot 10 and
`-`slot 12 -- the two slots swapped and the imaginary sign inverted. Check it against the
first row: interpreter `asin #c(0 1)` is `#C(0.0 0.8813735870195428)`, so slot 12 is `0.0`
and slot 10 is `-0.8813735870195428`; `pi/2 - (-0.88137)` is exactly the `2.4521699138144393`
the JVM prints, and `-`slot 12 is the `-0.0` beside it.

**`U1_TAN`/`U1_TANH` clobber the denominator's real part.** The shared body stores
`s.re`->6, `s.im`->8, `c.re`->14, `c.im`->2, then overwrites slot 14 with `|c|^2`
(`14*14 + 2*2`). The quotient that follows still reads slot 14 where it means `c.re`:
it computes `(s.re*|c|^2 + s.im*c.im)/|c|^2` and `(s.im*|c|^2 - s.re*c.im)/|c|^2` instead of
`(s.re*c.re + s.im*c.im)/|c|^2` and `(s.im*c.re - s.re*c.im)/|c|^2`. That is why the pure
cases degenerate to the NUMERATOR: for `#c(0 1)` the `c.im` term is zero and the `|c|^2`
factors cancel, leaving `sinh(1)` where `tanh(1)` belongs. `|c|^2` needs a slot of its own.

## What to do

1. Failing tests first (global rule): add the eleven rows above to
   `JvmLispCompilerTest#compileAndRunComplex*` as a JVM-vs-interpreter pin, not as literal
   expected strings -- the point of the mirror is that the two agree.
2. Fix both bodies as diagnosed. Then audit the remaining `_cu1` arms the same way: every one
   that reuses a slot after `emitComplexLog`/`emitAsinInto` is a candidate, and the test set
   that missed these three had no `tan`/`tanh`/`acos` complex case at all.
3. Re-run all four backends (`.kb/running-backends.md`) -- WASM has its own, different, break
   in this family (`[[766-wasm-phase-is-wrong-when-the-real-part-is-a-zero]]`).
4. `.kb/jvm-complex.md` gains a line under "Known corners" saying the eleven `_cu1` arms are
   pinned individually against the interpreter, so a slot reuse cannot go unnoticed again.

## Acceptance

Every row above identical between `java -jar ... p.lisp` and the compiled `java P`;
`ci-spec.yaml` grown a complex-transcendental case so the E2E lane covers the family;
existing `compileAndRunComplex*` cases unchanged.

## Related

- `[[766-wasm-phase-is-wrong-when-the-real-part-is-a-zero]]` -- the same sweep, the other backend
- `[[756-exp-1-prints-one-more-digit-on-aarch64-in-the-jvm-backend]]` -- a separate, last-digit
  defect in the same test method (`compileAndRunComplexExptExpLogTrig`), whose fix is the same
  rework step 1 asks for: pin the JVM against the interpreter, not against a literal string.
  That method is red on aarch64 today, so expect it red before touching anything here.
- `[[764-complex-asin-and-acos-pick-the-wrong-branch-on-the-cut]]` -- blocked on this one
- `[[037-number-extensions]]`
