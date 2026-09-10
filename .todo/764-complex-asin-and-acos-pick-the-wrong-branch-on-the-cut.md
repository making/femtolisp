# 764. Complex `asin`/`acos` pick the wrong branch ON the cut, and lose the exact zero on the real axis

Difficulty: High

Filed 2026-09-10, from a conformance sweep of the CL complex-number surface against SBCL
2.6.5 on the same machine. `asin` and `acos` of a complex answer the right value everywhere
EXCEPT on their branch cuts, and they never answer an exactly-real result for an exactly-real
argument.

RontoLisp has ONE float type, so the values below are written the way RontoLisp prints a
float -- no `d0` suffix.

## Symptom 1: the branch is chosen by the sign of the zero, not by the cut

`asin` and `acos` cut the real axis outside `[-1, 1]`. CLHS 12.1.3.3 fixes which side the
value ON the cut is continuous with: for `x > 1`, quadrant IV; for `x < -1`, quadrant II.
RontoLisp instead lets the SIGN of a zero imaginary part choose the side, so it disagrees with
both SBCL and CLHS on exactly half the cases:

| input | RontoLisp today | SBCL |
|---|---|---|
| `(asin #c(2d0 0d0))` | `#C(1.5707963267948966 1.3169578969248164)` | `#C(1.5707963267948966 -1.3169578969248166)` |
| `(asin #c(2d0 -0d0))` | `#C(1.5707963267948966 -1.3169578969248166)` | `#C(1.5707963267948966 -1.3169578969248166)` |
| `(asin #c(-4d0 0d0))` | `#C(-1.5707963267948966 2.0634370688955617)` | `#C(-1.5707963267948966 2.0634370688955608)` |
| `(asin #c(-4d0 -0d0))` | `#C(-1.5707963267948966 -2.0634370688955608)` | `#C(-1.5707963267948966 2.0634370688955608)` |
| `(acos #c(2d0 0d0))` | `#C(0.0 -1.3169578969248164)` | `#C(0.0 1.3169578969248166)` |
| `(acos #c(2d0 -0d0))` | `#C(0.0 1.3169578969248166)` | `#C(0.0 1.3169578969248166)` |
| `(acos #c(-4d0 0d0))` | `#C(3.141592653589793 -2.0634370688955617)` | `#C(3.141592653589793 -2.0634370688955608)` |
| `(acos #c(-4d0 -0d0))` | `#C(3.141592653589793 2.0634370688955608)` | `#C(3.141592653589793 -2.0634370688955608)` |

Off the cut both agree, which is what makes this a branch-selection bug and not an accuracy
one -- the limits from either side are already right:

| input | expected (both agree today) |
|---|---|
| `(asin (complex 2d0 1d-10))` | `#C(1.5707963267371616 1.3169578969248166)` |
| `(asin (complex 2d0 -1d-10))` | `#C(1.5707963267371616 -1.3169578969248166)` |
| `(asin (complex -4d0 1d-10))` | `#C(-1.5707963267690768 2.0634370688955608)` |
| `(asin (complex -4d0 -1d-10))` | `#C(-1.5707963267690768 -2.0634370688955608)` |

Note what the two tables say together: SBCL's value on the cut equals its limit from ONE
side and is returned for BOTH zero signs, so SBCL discards the sign of the zero. RontoLisp
honours the sign of the zero and therefore disagrees on one of the two. Decide which contract
this project wants and write it down in `.kb/`:

- **Follow SBCL/CLHS** -- the value on the cut is fixed by the sign of the REAL part, the
  imaginary zero's sign is ignored. Interoperable; the table above becomes the pin verbatim.
- **Honour the signed zero** -- `+0.0i` takes the limit from above, `-0.0i` from below, the
  way `sqrt` and `log` already do here and in SBCL (`(sqrt #c(-1d0 0d0))` is `#C(0.0 1.0)`
  and `(sqrt #c(-1d0 -0d0))` is `#C(0.0 -1.0)` on both). Self-consistent, but it makes
  `asin`/`acos` disagree with SBCL for `+0.0i` above `1` and for `-0.0i` below `-1`.

The recommendation is the first: `sqrt`/`log` agree with SBCL today, and matching SBCL on
`asin`/`acos` too costs nothing a program can observe except on the cut, where a program that
cares has to say which side it means anyway. Whichever is chosen, the four `-0d0` rows and the
four `0d0` rows must both be pinned, so the contract cannot drift back silently.

## Symptom 2: a real argument does not give an exactly-real result

For `x` inside `[-1, 1]`, `asin (complex x 0.0)` must have an imaginary part of exactly
`0.0`. RontoLisp leaks `2^-53` out of the general formula:

| input | RontoLisp today | SBCL |
|---|---|---|
| `(asin (complex -0.5d0 0d0))` | `#C(-0.5235987755982989 1.1102230246251565e-16)` | `#C(-0.5235987755982989 0.0)` |
| `(asin (complex 0.5d0 0d0))` | `#C(0.5235987755982989 1.1102230246251565e-16)` | `#C(0.5235987755982989 0.0)` |
| `(asin (complex 0d0 0d0))` | `#C(0.0 -0.0)` | `#C(0.0 0.0)` |
| `(asin (complex 1d0 0d0))` | `#C(1.5707963267948966 -0.0)` | `#C(1.5707963267948966 0.0)` |
| `(acos (complex -0.5d0 0d0))` | `#C(2.0943951023931957 -1.1102230246251565e-16)` | `#C(2.0943951023931953 0.0)` |
| `(acos (complex 0.5d0 0d0))` | `#C(1.0471975511965976 -1.1102230246251565e-16)` | `#C(1.0471975511965979 0.0)` |

`1.1102230246251565e-16` is not a rounding artefact of the last operation; it is the general
`-i log(iz + sqrt(1 - z^2))` path carrying a residue that a real-axis arm would not produce.
The WASM backend already answers `#C(1.0471975511965974 0.0)` for the `acos` row -- an exact
zero -- from a DIFFERENT formula, so the fix has a working reference inside the repo.

While in the formula: the interpreter's `asin` is about 2 ulp off SBCL's for a pure imaginary
argument -- `(asin #c(0d0 1d0))` is `#C(0.0 0.8813735870195428)` here and
`#C(0.0 0.881373587019543)` there, and the error shows up amplified in the round trip
(`(sin (asin #c(0d0 1d0)))` is `#C(0.0 0.9999999999999997)` here, `#C(0.0 1.0)` there).
Expect a formula that computes `asin` from `atan`/`log1p`-shaped pieces rather than the naive
square root to fix both symptoms at once.

## What to do

1. Failing tests first (global rule): `LispEvaluatorTest` with both tables, then the JVM and
   WASM mirrors -- but the JVM's complex `acos` is separately broken today
   (`[[765-jvm-complex-acos-tan-and-tanh-answer-wrong-values]]`), so land that first or its
   red will mask this one.
2. Pick the branch-cut contract, write it into `.kb/jvm-complex.md` and `.kb/wasm-complex.md`
   (both describe the unary family), and implement it once in a shape the three complex
   implementations can share the reasoning of.
3. Give `asin`/`acos` a real-axis arm so `imagpart` is exactly `0.0` (or `-0.0`, whichever
   the contract says) for a real argument inside `[-1, 1]`.
4. All four backends (`.kb/running-backends.md`); a `ci-spec.yaml` case for the cut.

## Acceptance

Both tables green on all four backends within the documented WASM tolerance (the exact-zero
rows exact on all four, since a zero has no tolerance); `(sin (asin z))` and `(cos (acos z))`
round-trip to within a few ulp for `z` in `#c(0 1)`, `#c(1 1)`, `#c(2 0)`, `#c(-4 0)`.

## Related

- `[[765-jvm-complex-acos-tan-and-tanh-answer-wrong-values]]` -- blocks the JVM mirror
- `[[766-wasm-phase-is-wrong-when-the-real-part-is-a-zero]]` -- blocks the WASM mirror
- `[[763-real-arguments-outside-the-real-domain-answer-nan]]` -- the real-argument twin
- `[[037-number-extensions]]`
