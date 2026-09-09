# phase

`(phase number)`

Returns the angle of `number` in the complex plane as a float: `atan2` of the imaginary and real parts for a complex, `0.0` for a non-negative real and pi for a negative one. Works on all four backends (the WASM backends compute the transcendental in software, so the last ulp may differ there).

```lisp
(phase 5) ; => 0.0
```

```lisp
(phase -5) ; => 3.141592653589793
```
