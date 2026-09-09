# 755. WASM: complex add/sub/mul breaks when the program renders a condition

Difficulty: High

Filed 2026-09-09 from `.todo/754` corpus work (all probes under `timeout`;
pristine-HEAD reproduces, so this predates 751-754).

## Repro

```lisp
(print (+ #c(1 2) #c(3 4)))
(print (handler-case (error "x") (error (e) (princ-to-string e))))
```

`wasmtime run` traps after the first line is skipped: stderr
`Unhandled condition: Expected integer, got: #C(1 2)` + `unreachable`.
Either form alone works; `(handler-case ... (error (e) :caught))`
(bound but unused) with the arithmetic also works.

## Narrowing (all on WASM GC Preview 1; component identical)

- Toxic: binary `+ - *`, `1+`, `1-` (also `(+ #c(1 2) 1)`; handler before or
  after the arithmetic; the rendered signal may be an unrelated plain error).
- Safe in the same toxic program: unary `-` and `/`, binary `/`, exact
  `expt`, `abs`, `sqrt`, `signum`, `conjugate`, `realpart`/`imagpart`/`phase`,
  the `complex` constructor, `#C` literals, predicates, `typep`/`subtypep`,
  `coerce`, and every `_as_f64`/`_type_err_*` error funnel.
- So the fault is specific to the `_cadd`/`_csub`/`_cmul` folds (and the
  `1+`/`1-` expansions that route through them), and only when the program
  also renders a condition object (`princ-to-string e`, `format nil "~a" e`).

## Suspect

Something the condition-report routing injects (`lazyConditionMessages`,
`%handler-clusters%`, report defuns) perturbs the `_c*` folds' operand
handling: the culprit printed is the whole holder, and the prefix is the
integer funnel's, as if a holder reached `_rat_num`/`_int_val` instead of
being split into real parts first. The `_cneg`/`_cdiv` folds share the same
`emitComplexReal`/`emitComplexImag` extraction yet survive, so compare the
linear-fold bodies against them first, then the function/type emission order
against the report injection point.

## Constraint for the fix

`.todo/754` deliberately pins no `+ - *` (and no `1+`/`1-`) in `ci-spec.yaml`
because every corpus case shares one concatenated WASM program with the
pre-existing `princ-to-string` handlers. When this is fixed, add those cases
(the exact-contagion `(+ #c(1 1/2) #c(1 1/3))` -> `#C(2 5/6)` first) and
re-run the Native Image E2E leg per AGENTS.md.
