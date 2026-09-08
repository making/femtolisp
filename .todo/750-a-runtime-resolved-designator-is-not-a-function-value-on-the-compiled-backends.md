# A runtime-resolved designator is not a function VALUE on the compiled backends

When the compiler cannot read a call designator, all three compiled backends keep the SYMBOL as
the callable value: it dispatches (the `_lookup` registry answers NAME -> funcId at the call site)
but it is not a function object. Measured 2026-09-09:

```lisp
(defun foo () 1)
(let ((fn (symbol-function (car (list 'foo)))))
  (print (functionp fn))   ; interpreter T, SBCL T -- JVM/WASM P1/component ALL NIL
  (print fn))              ; interpreter #<function FOO>, SBCL #<FUNCTION FOO>
                           ;   -- JVM/WASM P1/component ALL print FOO
(funcall fn))              ; 1 on all four (dispatch works)
```

So `(symbol-function <computed symbol>)`, `(coerce <computed symbol> 'function)` and the other
designator-to-function conversions answer different VALUES -- and therefore different printed text
and different `functionp` -- on the interpreter than on any compiled backend. This was found while
closing 434's one-text print contract: the four agree on every value that IS a function, and this
case escapes the contract because on the compiled side the value is a symbol, not a function.

The fix shape is representation, not printing: a run-time name resolution must BOX the resolved
funcId as a function value (and the print/name tables must answer its name, which the
compile-time `valueFuncIds` gate cannot predict -- see `.kb/core-representation.md`, "A function
value prints its registered NAME"). Decide there whether `functionp`/`type-of` and the print tag
follow automatically once the box exists, and pin all four backends plus SBCL on
`functionp`/printed-text/`funcall` for the computed-designator shapes.
