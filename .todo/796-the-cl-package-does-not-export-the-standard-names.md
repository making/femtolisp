# The `common-lisp` package does not export the standard names

Difficulty: Medium

`(find-symbol "BIT-AND" 'common-lisp)` answers `NIL NIL` where CL requires
`BIT-AND :EXTERNAL`. CLHS 11.1.2.1 makes the CL package's external list the
**978 standard names**, independently of whether an implementation's function
for one exists -- the name is exported; calling it is a separate question.

Measured on the ANSI suite 2026-09-12: `symbols/cl-symbols.lsp`'s
`test-if-not-in-cl-package` probe is **187 failing tests**, every one of the form
`FAIL SYMBOL-<NAME> got (T) want (NIL)`. It is the single largest row in the
`symbols` chapter (214 failures total) and the cheapest-looking one on the board
that nobody owns.

## What the probe does

```lisp
(defun test-if-not-in-cl-package (str)
  (multiple-value-bind (sym status) (find-symbol (string-upcase str) 'common-lisp)
    (or (not (eqt status :external))
        ...)))          ; also rejects a prohibited plist indicator
```

So the fix is the external NAME LIST, not the operators: `&aux`, `**`, `///`,
`*read-base*`, `add-method`, `arithmetic-error-operands`, `bit-and`,
`array-in-bounds-p` and ~180 more.

## Where it lives

`PackageRegistry` + `.kb/symbol-runtime-api.md` ("the accessibility status as the
second value", landed 2026-08-12) and `.kb/packages.md`. `find-symbol`'s status
is already answered on all four backends and the compile paths FOLD a literal
`(find-symbol "CAR" 'common-lisp)` to a constant -- so the name list must be one
source both the interpreter and that fold read, or the two answer differently.

## The judgement to make first

A name exported without a binding is a `find-symbol`/`do-external-symbols`
answer, not a promise that the operator works: `(bit-and a b)` must still signal
`undefined-function`. Check that adding ~200 names to the CL external set does
NOT change:

- `.kb/library-defun-pruning.md`'s reference scan (a name becoming resolvable
  must not make a pruned defun look referenced),
- `.kb/lisp2-namespaces.md` / the `cannot redefine the standard operator` guard
  (50 ANSI tests already fail on it; a program that `defun`s a name we do not
  implement must not start being rejected),
- the resolver's bare-name lowering in a user package.

Rank the overlap before quoting the 187: the `bit-*` family (~310 tests,
`.todo/043`/`.todo/180`) and the stream constructors (126, `.todo/387`) would
each fix their own `SYMBOL-*` row on the way. The 187 is what closing the LIST
alone wins.
