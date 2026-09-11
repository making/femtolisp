# log

`(log number)` `(log number base)`

Returns the natural logarithm (base e) of `number` as a float. With a `base` the answer is the logarithm of `number` in that base, which is exactly the quotient `(/ (log number) (log base))` -- no special case makes an exact power exact, and none is needed: `(log 8 2)` is `3.0` and `(log 1024 2)` is `10.0` from the plain quotient on the interpreter and the JVM (on WASM the software approximation below rides through the quotient, so those land within about `1e-9` of the integer rather than on it). The interpreter and JVM backends compute it with `Math.log`; the WASM backend uses a software approximation (exponent extraction plus a polynomial series), so its result may differ slightly in the least significant digits. The IEEE edges match everywhere: `(log 0.0)` is `-Infinity`. A negative argument leaves the real line and answers the principal logarithm in the complex plane, the way `sqrt` roots a negative: `(log -1)` is `#C(0.0 3.141592653589793)`, and `(log -100)` has real part `ln 100` and imaginary part pi. The type of the answer therefore depends on the value, not on how the argument was written -- `(let ((x -1d0)) (log x))` is complex on every backend.

Each logarithm takes that escape on its own, so `(log -8d0 2d0)` answers the plane -- real part `3`, imaginary part `pi / ln 2` -- and a complex `number` or `base` is likewise just the quotient of two complex logarithms.

```lisp
(log 1) ; => 0.0
```
