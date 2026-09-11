# asin acos atan

`(asin number)` `(acos number)` `(atan number)`

The inverse trigonometric functions, each returning an angle in radians as a float. `asin` is the arcsine, `acos` the arccosine, and `atan` the arctangent. Only the one-argument `atan` is supported -- there is no two-argument `(atan y x)` form. All three work on every backend: the interpreter and JVM use `Math.asin`/`Math.acos`/`Math.atan`, while the WASM backend computes `atan` with a software approximation (argument folding plus a Taylor series, ~1e-15 relative error) and derives `asin`/`acos` from it, so a WASM result can differ from the interpreter's and the JVM's in the last digit or two. `(asin 1)` is exactly `pi/2`, `(acos 1)` exactly `0.0`. An `asin`/`acos` argument outside `[-1, 1]` leaves the real line and answers the plane rather than `NaN` -- `(asin 2)` is `#C(1.5707963267948966 -1.3169578969248166)` and `(acos -4)` is `#C(3.141592653589793 -2.0634370688955608)` -- so the type of the answer depends on the value, and `(let ((x 2d0)) (asin x))` is complex on every backend.

A complex argument answers the plane. `asin` and `acos` are assembled from the two square roots `sqrt(1-z)` and `sqrt(1+z)`, so a complex whose imaginary part is zero and whose real part lies inside `[-1, 1]` answers an exactly real value -- `(asin (complex 0.5d0 0d0))` has imaginary part `0.0`, not a rounding residue. Both cut the real axis outside `[-1, 1]`, and the value on the cut is the one continuous with quadrant IV above `1` and quadrant II below `-1`: the side follows the sign of the real part, and the sign of an imaginary zero does not move it, so `(asin #c(2d0 0d0))` and `(asin #c(2d0 -0d0))` are the same value. (`sqrt` and `log` differ here -- for them the sign of an imaginary zero does pick the side.)

```lisp
(atan 0) ; => 0.0
```
