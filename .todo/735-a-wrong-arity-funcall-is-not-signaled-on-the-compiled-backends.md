# A wrong-arity funcall is not signaled on the compiled backends

Difficulty: Medium

`(funcall (lambda (x) x) 1 2)`, `(funcall #'f 1 2)` for a one-parameter `defun` and
`(apply #'f '(1 2))` signal `Function expects 1 argument, got 2` on the interpreter
(a `program-error` since `.todo/680`), but the compiled backends do not detect the
mismatch at all:

| call | interpreter | JVM | wasm-GC |
|---|---|---|---|
| `(funcall (lambda (x) x) 1 2)` | program-error | `NIL` | trap (`unreachable`) |
| `(funcall #'f 1 2)` | program-error | `NIL` | trap |
| `(funcall #'identity 1 2)` | program-error | `NIL` | trap |
| `(funcall #'car)` | program-error | `NIL` | trap |
| `(apply #'f '(1 2))` | program-error | `1` | trap |

Measured 2026-09-08 (`java Arity` / `wasmtime run -W exceptions=y arity.wasm`, the
six-probe program under `handler-case` with an `error` clause). The JVM dispatcher
(`_invoke_N`, `JvmRuntimeBuilder.buildDispatchMethod` -- "exact-arity matching EXCLUDES
variadics", `.kb/lambda-lists.md`) finds no arity match and answers nil; the wasm
dispatch (`WasmRuntimeBuilder.buildDispatchBody`) reaches its `unreachable`. Only a
DIRECT call `(f 1 2)` is checked, and at compile time (`JvmLambdaCompiler.compileCall`
for an inline lambda, the defun call compilers for a named one). A silent nil is the
worst of the three: the ANSI suite's `signals-error ... program-error` tests would
pass on the interpreter and silently return a wrong value compiled.

## How to do it

1. JVM: the dispatcher's no-match arm throws a `RuntimeException` spelled like the
   interpreter (`Function expects N argument(s), got M`) instead of returning nil,
   and `JvmHandlerCaseCompiler.emitRawFailureTest` classifies the `Function expects `
   prefix as `program-error` (the `Expected integer, got: ` precedent: a
   bytecode-emitted throw site has no channel for a class), which means one more
   entry in `LispMacroExpander.rawFailureConditionClasses()` and its ordered switch.
2. wasm-GC: the no-match arm throws a `$lisp-cond` in EH mode carrying the same text
   (the `_type_err_int` precedent, `WasmEmitHelper.buildTypeErrBody`; interned early
   beside the `Expected ...` prefixes) and stays a bare `unreachable` outside it. The
   class diverges the way the arithmetic one does (a `simple-error` there) unless the
   instance gates learn the site.
3. Pin with a ci-spec case beside `argument-shape-errors-signal-program-error`
   (`.kb/error-handling.md`, "Argument-shape errors signal a catchable
   program-error", which records the gap).
4. `--no-gc` keeps trapping.

Found while closing `.todo/680` (2026-09-08).
