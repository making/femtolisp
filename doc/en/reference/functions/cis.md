# cis

`(cis number)`

Returns the point on the unit circle at the angle `number` radians: `(cis x)` is `e^{ix}`, that is `(cos x, sin x)`. The result is always a complex number, even for a real argument. A complex argument decays toward the origin: `cis` of `re + i·im` is `(e^{-im} cos re, e^{-im} sin re)`. The interpreter and the JVM compute it from the hardware `exp`/`cos`/`sin`; the WASM backend derives it from its software approximations, so the least significant digits may differ there. `(cis 0)` is exactly `#C(1.0 0.0)` on every backend.

```lisp
(cis 0) ; => #C(1.0 0.0)
```
