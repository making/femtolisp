# 766. WASM `phase` (and every complex function built on it) is wrong when the real part is `±0.0`

Difficulty: Medium

Filed 2026-09-10, from a cross-backend sweep of the complex transcendentals. On both WASM
backends `phase` answers the IMAGINARY PART itself where `+0.0` is the real part, and `±pi`
where `-0.0` is -- instead of `±pi/2`. Everything that routes through it (`log` of a pure
imaginary, and therefore `asin`, `acos` and `atan` whose formulas take a log of one) is wrong
with it.

RontoLisp has ONE float type, so the values below are written the way RontoLisp prints a
float -- no `d0` suffix. Reproduce with
`java -jar target/rontolisp-0.1.0-SNAPSHOT-exec.jar p.lisp -o p.wasm && wasmtime run p.wasm`;
the `--component` output does the same.

## The red

| input | interpreter / JVM (correct) | WASM today |
|---|---|---|
| `(phase #c(0d0 1d0))` | `1.5707963267948966` | `1.0` |
| `(phase #c(0d0 3d0))` | `1.5707963267948966` | `3.0` |
| `(phase #c(0d0 -3d0))` | `-1.5707963267948966` | `-3.0` |
| `(phase #c(-0d0 1d0))` | `1.5707963267948966` | `3.141592653589793` |
| `(phase #c(-0d0 -1d0))` | `-1.5707963267948966` | `-3.141592653589793` |
| `(phase #c(0d0 0d0))` | `0.0` | `0.0` (already right) |
| `(phase #c(0d0 -0d0))` | `-0.0` | `-0.0` (already right) |
| `(phase #c(-0d0 0d0))` | `3.141592653589793` | `3.141592653589793` (already right) |
| `(phase #c(-0d0 -0d0))` | `-3.141592653589793` | `-3.141592653589793` (already right) |
| `(log #c(0d0 1d0))` | `#C(0.0 1.5707963267948966)` | `#C(0.0 1.0)` |
| `(log #c(0d0 -2d0))` | `#C(0.6931471805599453 -1.5707963267948966)` | `#C(0.6931471805599453 -2.0)` |
| `(asin #c(2d0 0d0))` | `#C(1.5707963267948966 1.3169578969248164)` | `#C(0.2679491924311228 1.3169578969248164)` |
| `(asin #c(-4d0 0d0))` | `#C(-1.5707963267948966 2.0634370688955617)` | `#C(-0.12701665379258298 2.063437068895561)` |
| `(acos #c(2d0 0d0))` | `#C(0.0 -1.3169578969248164)` | `#C(1.3028471343637738 -1.3169578969248164)` |
| `(acos #c(-4d0 0d0))` | `#C(3.141592653589793 -2.0634370688955617)` | `#C(1.6978129805874795 -2.063437068895561)` |
| `(atan #c(1d0 1d0))` | `#C(1.0172219678978514 0.4023594781085251)` | `#C(0.7318238045004031 0.4023594781085251)` |

The interpreter column agrees with SBCL 2.6.5 on every row. The affected rows are all cases
where the argument -- or an intermediate `iz ± sqrt(...)` -- lands exactly on the imaginary
axis, which is why `(asin #c(1d0 1d0))` and `(acos #c(0d0 1d0))` look fine and the real-axis
arguments do not. This is NOT the documented software-core imprecision
(`.kb/wasm-complex.md`, "Transcendentals"): those differences are ~1e-11 relative, these are
whole radians.

## The cause

`WasmComplexCompiler.emitAtan2Into`, the `x == 0` rung. Its comment says
"+0 -> y itself (signed zero preserved), -0 -> copysign(pi, y)", which is only correct when
`y` is ALSO zero. `Math.atan2` -- which the interpreter and the JVM use, and which the other
three rungs of this function match -- splits it further:

```
y != 0            -> copysign(pi/2, y)      ; whichever sign x's zero has
y == 0, x is +0.0 -> copysign(0.0, y)
y == 0, x is -0.0 -> copysign(pi, y)
```

So the rung needs a `y == 0` test in front of the existing `copysign(1, x) > 0` test, and the
`y != 0` arm becomes `copysign(pi/2, y)`. The four already-right rows above are exactly the
`y == 0` ones the current code happens to cover.

## What to do

1. Failing test first (global rule): the table above in
   `WasmLispCompilerIntegrationTest#compileAndRunComplex*`, as a WASM-vs-interpreter pin with
   the documented `isCloseTo` tolerance -- except the signed-zero rows, which are exact.
2. Fix the `x == 0` rung. Nothing else in the file should need to move: the three other rungs
   already match `Math.atan2`, and `phase`, complex `log`, `asin`, `acos` and `atan` all reach
   `atan2` through this one helper.
3. Both WASM legs (preview 1 AND `--component`, `.kb/running-backends.md`) plus the JVM and
   interpreter legs to confirm nothing moved there.
4. `.kb/wasm-complex.md`: the "Transcendentals" paragraph credits `phase` with "`atan2` from
   the atan core plus quadrant assembly -- `copysign` tells `+0` from `-0`". Say which
   `Math.atan2` rungs it reproduces, so the next reader can check the claim against the four
   cases rather than the two.

## Acceptance

Every row above matching the interpreter on both WASM backends (exactly for the signed-zero
rows, within the documented tolerance otherwise); a `ci-spec.yaml` case covering a pure
imaginary `phase`/`log`, so the E2E lane holds it.

## Related

- `[[765-jvm-complex-acos-tan-and-tanh-answer-wrong-values]]` -- the same sweep, the other backend
- `[[762-atan-and-log-take-only-one-argument]]` -- a real `atan2` would land on this helper
- `[[764-complex-asin-and-acos-pick-the-wrong-branch-on-the-cut]]` -- blocked on this one
- `[[037-number-extensions]]`
