# log

`(log number)`

Returns the natural logarithm (base e) of `number` as a float. Only the one-argument form is supported -- there is no `(log number base)` form for an arbitrary base. The interpreter and JVM backends compute it with `Math.log`; the WASM backend uses a software approximation (exponent extraction plus a polynomial series), so its result may differ slightly in the least significant digits. The IEEE edges match everywhere: `(log 0.0)` is `-Infinity`. A negative argument leaves the real line and answers the principal logarithm in the complex plane, the way `sqrt` roots a negative: `(log -1)` is `#C(0.0 3.141592653589793)`, and `(log -100)` has real part `ln 100` and imaginary part pi. The type of the answer therefore depends on the value, not on how the argument was written -- `(let ((x -1d0)) (log x))` is complex on every backend.

```lisp
(log 1) ; => 0.0
```
