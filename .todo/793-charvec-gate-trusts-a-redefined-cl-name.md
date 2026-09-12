# The charvec gate trusts a user defun the backend does not dispatch to

**Status:** open. Found 2026-09-12 by the filer of
[`789`](789-string-boundary-drags-in-the-charvec-normalizer.md), probing the gate that
item landed (`d567a5402`).

Difficulty: Low

## The bug

A program that defines its own function on a `COMMON-LISP` name the wasm backend
intercepts **no longer compiles**. It compiled before `d567a5402`.

```lisp
(rontolisp:wasm-import 'emit :from "env" :as "emit" :params '(:string) :returns nil)
(defun subseq (s a b) (if (< a b) s s))
(defun go () (emit (subseq "abcdef" 1 3)))
(rontolisp:wasm-export 'go :as "Go" :params '() :returns nil)
```

```
$ rontolisp gateC.lisp -o gateC.wasm --no-wasi --optimize=size
error: gateC.lisp:3:20: internal: %SUBSEQ-RUNTIME is injected runtime, compiled as if a
character vector were possible, and the program reaches it with the charvec gate closed --
take the operator that lowers to it off CHARVEC_FREE_OPERATORS
```

Remove the `(defun subseq ...)` and the same program builds (2,904 bytes; the gate opens,
reporting `%STR-FRESH`). The user's definition is what breaks it.

Reproduced on `--no-wasi --optimize=size` for `subseq`, `copy-seq`, `reverse`
(`%SEQ-TO-LIST`), `string-upcase` and `string-trim` (the flipped-string-producer arm of
the same assertion). `concatenate` builds -- that call is not on an asserting path -- and
its module then emits `"ab"`, i.e. the standard operator, NOT the user's function.

## Why

`WasmLispCompiler.defunNames` collects every name the program defines and
`charvecFreeName` treats those names as safe, on the stated ground that "a call to one of
these is a call into code `charvecFreeProgram` is reading anyway".

For a `cl` name the backend intercepts, that ground is false, and **the compiler already
says so on the very same call site**:

```
warning: (defun CONCATENATE ...) redefines the COMMON-LISP function CONCATENATE, but a
(CONCATENATE ...) call site here compiles to the standard operator, so the definition is
not what runs (a #'CONCATENATE function value still names it). Defining a function on a
COMMON-LISP symbol has undefined consequences (CLHS 11.1.2.1.2): rename it, or shadow the
symbol in a package of your own
```

So the gate closes because it believes it has read the callee, the operator dispatch then
lowers to injected runtime that assumes a charvec is possible, and the assertion fires.

**The assertion is not the defect -- it is the reason this is a build error instead of a
silent wrong answer at the boundary, which is exactly the failure mode `789` was written
to avoid.** What is wrong is its input. Its *advice* is wrong too: the offending name is
not on `CHARVEC_FREE_OPERATORS` and taking anything off that list cannot fix this program.

## What to do

Exclude from `defunNames` every name the backend intercepts rather than resolving, using
the predicate that already exists for this exact question:

```java
ClRedefinitionWarnings.redefinesClFunction(name, userDefunNames)
// == userDefunNames.contains(name) && PackageRegistry.isClFunctionName(name)
```

A name the program defines that is NOT a `cl` function name stays safe as today. A name
that is one goes back to being read as an operator, which for a charvec-producing builtin
means the gate opens -- the module pays the normalization it would have paid before
`d567a5402`, which is the right answer, since that is the code that runs.

- Both `--optimize=size` and `--optimize`; the gate is level-independent.
- While there: `requireCharvecPossible`'s message should name the two ways it can be
  reached (an operator wrongly on the allowlist, OR a name in `defined` that the backend
  intercepts) or stop naming a remedy at all. It is read by whoever hits it, and it
  currently sends them to the wrong list.
- The interpreter and JVM backends do not gate anything, so nothing there changes.

## Tests

- A build test: the program above compiles, and its module still renders the `subseq`
  result correctly at the `:string` boundary (the existing node-host pattern --
  `WasmStringParamBoundaryE2eTest` is the shape).
- One per asserting family (`%SUBSEQ-RUNTIME`, `%SEQ-TO-LIST`, the flipped string
  producer), since they reach the assertion by different routes.
- A negative: a program defining a NON-`cl` name that shadows nothing keeps the gate
  closed (do not fix this by opening the gate for every user defun -- that would give back
  the 1,902 bytes `789` bought).

## Touch points

- `codegen/wasm/WasmLispCompiler.java` (`defunNames`, `charvecFreeName`,
  `CHARVEC_FREE_OPERATORS`'s javadoc)
- `codegen/wasm/WasmEmitHelper.java` (`requireCharvecPossible`'s message)
- `compiler/ClRedefinitionWarnings.java` (the predicate to reuse; no change expected)
- `.kb/wasm-gc-strings.md` / `.kb/string-accumulate-cost.md` if either states the gate
