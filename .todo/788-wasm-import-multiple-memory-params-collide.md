# `wasm-import`: two memory-typed parameters lower to the SAME buffer

**Status:** open. Found 2026-09-12 while measuring a `--no-wasi` module whose
host import took `(:string :string)`.

Difficulty: Medium

## The bug

A `rontolisp:wasm-import` whose parameter list holds more than one memory-typed
parameter (`:string`, `:s-expr`, in any combination) hands the host the SAME
`(ptr, ...)` for every one of them: each argument is written over the previous
one, so the host sees the LAST argument's bytes under every pointer, with the
earlier argument's length. Silent -- the module validates, instantiates and
runs; only the bytes at the boundary are wrong.

```lisp
(rontolisp:wasm-import 'js-two :from "env" :as "js_two"
                       :params '(:string :string) :returns nil)
(defun go2 () (js-two "AAAAAAAAAAAA" "BBBB"))
(rontolisp:wasm-export 'go2 :as "Go" :params '() :returns nil)
```

`rontolisp two.lisp -o two.wasm --no-wasi --optimize=size`, then a Node host
printing both pairs:

```
arg1 ptr 33089 len 12 = "BBBB\"AAAAAAA"
arg2 ptr 33089 len  4 = "BBBB"
```

Same with `'(:string :s-expr)`:

```
arg1 ptr 33089 len 12 = "(1 2 3)\"AAAA"
arg2 ptr 33089 len  7 = "(1 2 3)"
```

One memory-typed parameter is correct, and so is a memory-typed RESULT (the
host writes that one). The EXPORT direction is unaffected: an export's
`:string` parameters arrive as `(ptr,len)` the host already laid out.

Related: [`789`](789-string-boundary-drags-in-the-charvec-normalizer.md) proposes lowering a
LITERAL string argument straight to its `(ptr, len)` in the data segment, which sidesteps
this for literals -- it does not fix the general case, which is this item.

## Root cause

`WasmExportCompiler.emitStringResult` copies the string's GC-heap bytes into
linear scratch at `HEAP_PTR` and **deliberately does not advance it** -- correct
for its original caller, a single RESULT the host reads immediately after the
wrapper returns.

`WasmImportCompiler.emitUnboxParam` reuses it per PARAMETER (`case STRING`, and
`case S_EXPR` after `FUNC_PRIN1_TO_STR`). N memory-typed parameters therefore
stage N regions at one unadvanced base, and all of them must stay live
simultaneously across the host call.

The mark/restore bracket that would make this a stack already exists in the same
wrapper -- `markSlot`, `emitAllocInto`, `emitHeapRestore` -- but it is gated on
`bytesStaging`, i.e. it is emitted only for `:bytes` parameters, which allocate
properly through `__ronto_alloc`.

## What to do

Stage `:string`/`:s-expr` parameters the way `:bytes` ones already are: take the
mark once when the wrapper has ANY memory-typed parameter (not only a `:bytes`
one), bump-allocate each parameter's region, and pop back to the mark after the
host call returns. Points to settle:

- The boundary is `(ptr,len)` of the CONTENT; `_str_to_mem` writes the internal
  surrounding quotes, which `emitStringResult` strips by `+1` / `-2`. An
  allocating variant has to keep that arithmetic.
- The `--reentrant` path frees park blocks instead of popping
  (`emitParkFrees`); a suspending import's string parameters have to survive the
  park, so they belong in park blocks too, freed alongside the `:bytes` ones.
- `emitStringResult` also serves a real RESULT and the export wrappers. Leave
  that caller on the non-advancing scratch (it is what `.kb/wasm-gc-strings.md`
  documents) and give the parameter path its own emitter, or the byte output of
  every existing module moves.
- Local-slot bookkeeping: `markSlot` / `bytesParamBase` / `resultPtrSlot` are
  computed from `numBytesParams`; a new per-string slot pair joins that run.

## Tests

- A structural pin in `WasmImportCompilerTest` (two `:string` parameters =>
  distinct staged bases).
- The real check is a HOST round-trip, and `wasmtime --invoke` cannot read the
  two pointers apart; this is the gap [`021`](021-wasm-export-memory-abi-ci-coverage.md)
  is about, so the two want the same JS harness.

## Touch points

- `src/main/java/am/ik/rontolisp/codegen/wasm/WasmImportCompiler.java`
  (`emitUnboxParam`, the `bytesStaging` block, the slot layout)
- `src/main/java/am/ik/rontolisp/codegen/wasm/WasmExportCompiler.java`
  (`emitStringResult`)
- `.kb/wasm-import.md` (the parameter ABI paragraph), `.kb/wasm-gc-strings.md`
  (the unadvanced-scratch rule and its one remaining caller)
