# 779. Complex division is the naive `(ac+bd)/(c^2+d^2)`, not Smith's

Difficulty: Medium

Filed 2026-09-11, out of `.todo/762`'s acceptance table: one row of it
(`(log -8d0 2d0)` = `#C(3.0 4.532360141827194)`) does NOT land, and the cause has no
logarithm in it.

All three complex divisions -- `Environment.divComplex`'s float arm,
`JvmComplexRuntimeBuilder.buildDiv`'s float path and
`WasmComplexRuntimeBuilder.buildDivBody` -- compute `(a+bi)/(c+di)` as

```
denom = c*c + d*d
re    = (a*c + b*d) / denom
im    = (b*c - a*d) / denom
```

SBCL computes Smith's form instead: fold on `|c| >= |d|`, `r = d/c`,
`den = c + d*r`, `re = (a + b*r)/den`, `im = (b - a*r)/den` (and the mirrored
branch otherwise). Measured against SBCL 2.2.9 on `linux/amd64`, 2026-09-11:

| call | here | SBCL |
|---|---|---|
| `(/ #c(2.0794415416798357d0 3.141592653589793d0) 0.6931471805599453d0)` | `#C(2.9999999999999996 4.532360141827194)` | `#C(3.0 4.532360141827194)` |
| `(log -8d0 2d0)` (the same quotient) | `#C(2.9999999999999996 4.532360141827194)` | `#C(3.0 4.532360141827194)` |

Two properties are at stake, and the first is the one a program notices:

1. **Accuracy.** A REAL divisor makes `d = 0`, so Smith's form degenerates to
   `a/c, b/c` -- ONE rounding per part. The naive form rounds `c*c`, then the
   product sum, then the quotient: three, and the last bit goes with them.
2. **Range.** `c*c + d*d` overflows for `|c|` above about `1.3e154` and flushes to
   zero below about `1.5e-162`, where the operands themselves are perfectly
   representable. Smith's fold never squares anything larger than the smaller part
   over the larger. `(/ #c(1d200 1d200) #c(1d200 1d200))` should be `1.0`.

## What to do

1. Failing tests first, against the table above and the two range cases.
2. One form, three implementations, and the `.kb` file says they must agree -- so
   change `divComplex`, `_cdiv` and `_c_div` together, never one alone.
3. The EXACT (rational) paths are already exact and must stay untouched; this is the
   float arm only. `exactDivComplex`'s division-by-zero funnel must keep answering
   where it does.
4. Decide the zero-divisor float edge DELIBERATELY and pin it: today
   `(/ #c(1d0 2d0) 0d0)` is `#C(NaN NaN)` (the naive `0/0`), where a part-wise divide
   would answer `#C(Infinity Infinity)`. Check SBCL and write down which one this
   implements and why.
5. `.todo/762` left one arm of this in place already: `_cdiv` / `_c_div` delegate a
   pair of NON-holder operands to the ungated real `_div` / `_rat_div`. That arm is
   the real-real case, not the real-divisor case, and it stays either way.
6. Re-measure `.kb/jvm-complex.md`'s and `.kb/wasm-complex.md`'s
   "two-argument `atan` and `log`" sections, which record the current numbers, and
   `doc/en`+`doc/ja`'s `log.md`, which deliberately avoids spelling the last bit.

## Acceptance

`(log -8d0 2d0)` answers `#C(3.0 4.532360141827194)` on the interpreter and the JVM
(WASM keeps its software-log error), the two range cases stop overflowing, and every
existing complex pin either holds or moves TOWARDS SBCL with the move recorded.

## Related

- `[[762-atan-and-log-take-only-one-argument]]` -- where this was measured
- `[[751-complex-core-value-reader-interpreter]]`, `[[754-complex-type-system-corpus-docs]]`
