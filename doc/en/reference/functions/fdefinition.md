# fdefinition

`(fdefinition symbol)`

The function value of a symbol, like [`symbol-function`](symbol-function.md) (setf-function names are not supported).

A quoted symbol literal (`(fdefinition 'car)`) resolves at compile time in the compilers; a runtime-computed symbol resolves late through the compiled name registry and answers the same function value -- like [`symbol-function`](symbol-function.md), with no deviations.

```lisp
(funcall (fdefinition 'car) '(1 2 3)) ; => 1
```

`fdefinition` is the same `setf` place as [`symbol-function`](symbol-function.md): `(setf (fdefinition 'name) fn)` installs `fn` as the symbol's global function definition.
