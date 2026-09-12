# A wrong-arity apply is not signaled on the compiled backends

Difficulty: Medium

The 2026-09-12 wrong-argument-count work made a count through a function VALUE a
catchable `program-error` on both compiled backends -- but only where it is a DISPATCH
MISS, i.e. `funcall`, `mapcar`, `sort`, a bare `(f x)` whose head is an
expression. `apply` is untouched:

| call | interpreter | JVM | wasm-GC |
|---|---|---|---|
| `(apply #'f '(1 2))` for a one-parameter `f` | program-error | `1` | `1` |
| `(apply #'f '())` | program-error | `NIL` | `NIL` |
| `(let ((h #'f)) (apply h '(1 2)))` | program-error | `1` | `1` |

Measured 2026-09-12 on all four backends. The reason is structural: the SPREAD
dispatcher (`_invoke_v` / `FUNC_DISPATCH_SPREAD`) carries a case for EVERY
callable and reads the required parameters out of the argument list with
car/cdr, which answer nil past its end -- so a short list BINDS nil and a long
one drops the tail, and neither is a dispatch miss. A literal
`(apply #'f list)` does not even reach the dispatcher: it compiles to a
physical direct call that walks the list itself (`JvmApplyCompiler`).

## Why it did not land with the `funcall` half

The check was written and measured, then removed. What it finds first is not a
user bug but **`.todo/192`**: cl-ppcre's scanner self-shadows
`(*reg-starts* *reg-starts*)` and a FAILING scan exits across the `advance-fn`
lambda, which the compile paths' special-binding restore did not cover. The
leaked one-element array then made a later register-free scan report a
register that is not there, and `regex-replace-all ... :simple-calls t` passed
that phantom register as a SECOND argument to a one-parameter replacement
function. With the check in place `ClPpcreE2eTest.compilesAndRunsOnJvm` went
red -- correctly, on a real corruption that was silent.

**Unblocked 2026-09-12**: `.todo/192` landed -- a special `let` is now an
unwind-protect region on both compile paths, restoring on every exit channel
(`.kb/dynamic-special-variables.md`), and `ClPpcreE2eTest` pins the exact
failing-register-scan sequence. Reordering the cl-ppcre exercise was never the
answer.

## How to do it

1. Per spread CASE, a count guard. The shape (required count doubled, plus one
   for a `&rest` tail) is a compile-time constant per case; the count is the
   list length.
   - JVM: `ALOAD_1; <shape>; INVOKESTATIC _arityChk` (~7 B per case) over a
     shared `_arityChk(Object argList, int shape)` that counts and throws
     `_arityMsg(shape, got)` -- both helpers already exist
     (`JvmRuntimeBuilder.buildArityMethods`).
   - wasm-GC: hoist the list length into a local before the `br_table`, then
     `local.get len; i32.const required; i32.ne` (`i32.lt_u` for a variadic)
     `-> set the shape, br $arityerr`, reusing the assembly block
     `WasmRuntimeBuilder.emitArityThrow` already emits. ~14 B per case.
2. The LITERAL `(apply #'f list)` direct call needs its own guard at the call
   site on both backends -- it bypasses the dispatcher entirely.
3. **Measure before landing.** The spread dispatcher is one case per callable
   and the ci-spec corpus reached 258 KB of it on wasm
   (`.kb/wasm-function-body-size.md`); 14 B per case over a 2,000-callable
   program is +28 KB, which may push it past `DISPATCH_PAGE_BUDGET_BYTES` and
   into paging. Use the `.kb/error-handling.md` table's method (minimal
   programs, JVM `.class` and wasm bytes, before/after).
4. Extend the ci-spec case `wrong-arity-funcall-signals-program-error`, whose
   comment currently PINS the gap, and the `apply` row of
   `JvmLispCompilerTest.compileAndRunWrongArityThroughAFunctionValueSignalsProgramError`.

Related: `.kb/error-handling.md` ("A wrong argument COUNT through a function
value"), `.kb/dynamic-special-variables.md` (the every-exit restore).
