# `complement` answers a ONE-argument lambda

Difficulty: Medium

`LispMacroExpander.expandComplement` lowers `(complement fn)` to
`(let ((f fn)) (lambda (x) (not (funcall f x))))`, and says why: CL's `complement` is
variadic, a variadic lowering needs `apply`, and `apply` drags the whole eval runtime into
every compiled program that uses it.

The cost is that a complemented EQUALITY designator -- the common use, two arguments --
fails at the call site with `Function expects 1 argument, got 2`. ANSI spells it directly:
`remove.order.2`, `delete.order.2` and `adjoin.order.2` all pass
`:test-not (progn ... (complement #'eq))`, and `BuiltinFunctionWrappers.sequenceScanFamily`
/ `positionFamily` build the first-class `#'remove`/`#'position` wrappers on
`(complement (getf kw :test-not))`, so `(apply #'remove ... :test-not #'eq)` is broken the
same way on the compile paths.

## How to do it

1. Measure the premise before believing it: compile a program that uses `complement` and
   compare the output size with `apply`-based and lambda-based lowerings (the size-report
   corpus is the instrument -- `size-report/README.md`). The claim "drags the whole eval
   runtime" was written for the JVM and WASM backends and may have moved.
2. If it holds, an arity-covering lowering that needs no `apply` is
   `(lambda (a &rest more) (not (if more (funcall f a (car more)) (funcall f a))))` -- CL
   conformance for 3+ arguments is then still missing, which is worth saying out loud in
   the javadoc rather than leaving implied.
3. Whatever the shape, `KeywordTail.complemented`
   (`.kb/sequence-designator-evaluation.md`) spells its own two-argument negation today to
   avoid this; it should go back to calling `complement` once `complement` can serve it.

Found while closing `.todo/772` (2026-09-11).
