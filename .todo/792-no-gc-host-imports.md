# `--no-gc` cannot call a host function, and it is the backend that is already small

**Status:** open. Measured 2026-09-12.

Difficulty: Medium

Fourth of the four items on the measurement in
[`789`](789-string-boundary-drags-in-the-charvec-normalizer.md).

## The finding

The measurement program's whole shape -- scalar arithmetic, string literals, exports a host
calls on demand -- is inside the `--no-gc` subset except for one thing:

```
error: rontolisp:wasm-import is not supported with --no-gc (use the default GC backend)
```

What that backend costs for the same work, `--no-gc --no-wasi --optimize=size`:

| Program | Bytes | (wasm-GC, same source) |
| --- | ---: | ---: |
| `fib` + `:s32` export | **123** | 2,281 |
| `(length "module initialized")` + `:s32` export | **288** | -- |
| `(length s)` over a `:string` param + `fib`, two exports | **468** | -- |

The GC backend's floor for an export-only module with no arithmetic and no strings is 321
bytes; `--no-gc` does the arithmetic AND the strings in less. It has no boxed value model to
carry: unboxed `i64`/`f64`, strings in linear memory, no rec group, no `i31`, no bignum
tier, and `inferTypes` -- a monotone fixpoint over the call graph that pins exported
parameters to their boundary designator -- already decides every local's representation
statically (`.kb/no-gc-scalar-wasm.md`).

Landing [`789`](789-string-boundary-drags-in-the-charvec-normalizer.md) and
[`790`](790-exact-integer-arithmetic-without-the-generic-fallback.md) brings the GC backend
to ~1 KB for this program. `--no-gc` would answer in ~400 bytes today if it could call out.
These are not competing plans: `789`/`790` pay on every program, this one pays on the
programs that fit the subset, and the subset is exactly the shape a host-driven module
tends to have.

## What to do

**1. `rontolisp:wasm-import` on `--no-gc`.**
The directive is already parsed backend-independently (`compiler/WasmImportDirective`,
shared with the JVM backend), so this is codegen only. The mechanism the GC backend uses --
each import becomes a synthetic defun with a placeholder call index, and
`am.ik.wasm.WasmImportInjector` rewrites the finished module -- is language-independent and
should be reused verbatim rather than re-derived; `NoGcWasmCompiler` already emits one
`fd_write` import when the program prints, so the import section is not new ground.

Types, narrowest first: `:s32`/`:s64`/`:float`/`:bool` are the backend's native
representations and need no boxing at all. `:string` is the one with a design question --
`--no-gc` strings already ARE `(ptr, len)` in linear memory, so the boundary is nearly
free, which is the opposite of the GC backend's problem in `789`. `:s-expr` has no reader
here and should stay rejected, with the error naming which types this backend takes.

**2. Then the same for `rontolisp:wit-import`**, which lowers to exactly one directive per
WIT function -- if item 1 reuses the shared machinery, this is a gate change and a test.

**3. Say what the subset is, in the error.**
`--no-gc` accepts only `(defun ...)` and `rontolisp:wasm-export` at top level, has no
`format`, no cons, no rank >= 3 arrays. A program that would fit but for one form gets a
message per rejection today; the refusal that matters is the FIRST one a user hits, and
"use the default GC backend" is an answer that costs them 10x. Worth a line naming what
the subset would need.

## The bigger question this raises

Two backends now infer types over the call graph: `NoGcWasmCompiler.inferTypes` (exact,
static, drives the representation) and the wasm-GC fusion classifier (per-expression, with
a total fallback). `790`'s mechanism 2 wants the first one's lattice on the second one's
backend. If `790` lands as written, the two should be ONE fixpoint with a richer lattice,
parameterized by what each backend can represent -- and that decision is easier to make
before this item adds a third caller of the no-gc one, not after.

## Touch points

- `codegen/wasm/NoGcWasmCompiler.java` (imports, the boundary lowering, `inferTypes`)
- `codegen/wasm/WasmImportCompiler.java` (the shared parts to lift, not to copy)
- `am/ik/wasm/WasmImportInjector.java` (already language-independent; reuse as is)
- `cli/CompileFrontend.java` / `RontoLispCli` (the `--no-gc` rejection gate)
- `.kb/no-gc-scalar-wasm.md`, `.kb/wasm-import.md`, `.kb/wit.md`
