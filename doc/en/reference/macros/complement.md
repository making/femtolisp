# complement

`(complement function)`

Returns a predicate answering the opposite of `function`: the result is `t` where `function` returns `nil` and vice versa. The returned predicate accepts any call of up to three arguments, so it serves an equality designator (`:test` / `:test-not`) as well as a one-argument predicate. Lite: Common Lisp's `complement` is variadic without bound and this one stops at three arguments, and `complement` expands inline so `#'complement` is not available.

```lisp
(funcall (complement #'evenp) 3) ; => T
```

```lisp
(remove-if (complement #'oddp) '(1 2 3 4 5)) ; => (1 3 5)
```

```lisp
(remove 3 (list 1 2 3 4) :test-not (complement #'eql)) ; => (1 2 4)
```
