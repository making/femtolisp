# The `:string` boundary drags 1,961 bytes of charvec normalization into every module

**Status:** open. Measured 2026-09-12.

Difficulty: Medium

First of four items on the same measurement:
[`790`](790-exact-integer-arithmetic-without-the-generic-fallback.md) (arithmetic),
[`791`](791-module-level-slack-globals-types-data-hooks.md) (module-level slack),
[`792`](792-no-gc-host-imports.md) (the other backend). **The shared measurement lives
here**; the other three quote it.

## The measurement

`bench.lisp` -- a reactor module for a host that calls in: two host imports, one taking
two `:string`s, a fixnum recursion, two exports. Nothing else.

```lisp
(rontolisp:wasm-import 'host-log :from "env" :as "host_log" :params '(:string) :returns nil)
(rontolisp:wasm-import 'host-set-text :from "env" :as "host_set_text"
                       :params '(:string :string) :returns nil)
(defun fib (n)
  (if (<= n 1) n (+ (fib (- n 1)) (fib (- n 2)))))
(defun init-app ()
  (host-log "module initialized")
  (host-set-text "status-badge" "running"))
(defun run-computation (n) (fib n))
(rontolisp:wasm-export 'init-app :as "InitApp" :params '() :returns nil)
(rontolisp:wasm-export 'run-computation :as "RunComputation" :params '(:s32) :returns :s32)
```

`rontolisp bench.lisp -o bench.wasm --no-wasi --optimize=size` = **4,563 bytes**, of which
the program's own code is **209** (`fib` 54, `init-app` 32, the two import wrappers 94, the
two export wrappers 21, `_initialize` 7). Sections: code 3,959 / 40 functions, types 210,
data 120, globals 79, exports 92, imports 32, functions 41, memory 3.

Cut the program down to isolate each half (same flags):

| Program | Bytes | Over the empty module |
| --- | ---: | ---: |
| one `(defun noop () nil)` + one export | 321 | -- |
| + one `:string` host import and a literal | 2,578 | 2,257 |
| + `fib` only (no strings at all) | 2,281 | 1,960 |
| both (the program above) | 4,563 | 4,242 |

So the module is 4.6% program and 95.4% runtime, and the runtime splits almost exactly in
two: the string boundary and the arithmetic. This item is the string half; `790` is the
other.

Two spikes, both measured on the program above (patches reverted; reproduce by editing the
two call sites named below):

| Build | Bytes | `wasm-opt -Oz` | gzip |
| --- | ---: | ---: | ---: |
| today | 4,563 | 2,508 | -- |
| this item's spike (drop the `_str_to_mem` preamble) | 2,661 | -- | -- |
| plus `790`'s spike (i31-only `+`/`-`/compare, i31 export return) | 1,030 | 816 | 685 |

Both spiked modules still run correctly under a Node host (`_initialize`, `InitApp`,
`RunComputation(20)` = 6765).

The `wasm-opt` column is a PROBE, not a proposed build step -- it was run to size the
opportunity before the spikes, and it mostly finds the same dead code this item and `790`
remove for reasons. Note the last row: once both land, an external optimizer has 214 bytes
left to find. What that residue is, and why it is small enough to write by hand, is
[`791`](791-module-level-slack-globals-types-data-hooks.md).

## This item

`WasmStringRuntimeBuilder.buildStrToMemBody` opens with
`WasmEmitHelper.emitCharvecToStrCall(w)`: every value crossing to linear memory is first
run through `_charvec_to_str`, which renders a mutable character vector into a
quote-framed runtime string and passes anything else through. Correct, and it costs
**1,961 bytes** in a module that never makes a character vector:

| Function | Bytes | What it is |
| --- | ---: | --- |
| `_charvec_to_str` | 517 | the renderer |
| `_charvec_p` | 281 | the marker test |
| (list walk over the vector header) | 398 | |
| (UTF-8 encode: measure + write) | 533 | |
| `_str_from_mem` | 87 | only reachable from the renderer |
| (a dead `"TRIVIAL-GRAY-STREAMS"` data segment) | 22 | see below |

The same call is inserted after the string operand of every string consumer (`char`,
`subseq`, `string=`, the case/trim/concat family, `write-string`, `read-from-string`,
`intern`, `make-symbol`) and at the entry of `_equal`/`_hash`/`_print_val`/`_princ_val`.
One `:string` parameter anywhere is enough to root the whole group.

## What to do

**1. Gate the normalization on the program being able to make a charvec.**
Whether a mutable character vector can exist is a static property of the source: it needs
`make-array`/`make-string` with `:fill-pointer`/`:adjustable`, `vector-push-extend`, a
`with-output-to-string`-family capture, `subseq`/`copy-seq` over a string, or one arriving
from the host. When none of those appears, `emitCharvecToStrCall` emits nothing and the
six functions above are never rooted. Shape it like the existing `Ctx` capability flags
(`usesRead`, `usesEval`, `memoryHelpers`), decided in the same pass that already scans the
program, and note that `NoWasiFilesystemStubs.rewrite` runs FIRST so the scan sees the
program actually compiled.

- Decide the analysis ON THE NOSE: a wrong "no charvec here" is a silent wrong answer at
  the boundary, not a crash. The list above is a starting point, not the answer; derive it
  from the constructors, not from the consumers.
- The same flag turns the insertion off at every other site in one place, so the win is
  larger on a program with string operations than on this one.

**2. Lower a LITERAL string argument straight to `(ptr, len)`.**
`(host-log "module initialized")` currently builds a runtime string from the data segment
(`_str_from_data`, 68 bytes), then copies it back into linear memory byte by byte
(`_str_to_mem`, 114 bytes) with its frame quotes, and the boundary strips them again with
`+1`/`-2`. The bytes are already in the data segment at compile time: a literal (or any
argument the compiler knows is a constant string) can pass its own `(ptr, len)` with no
call and no copy, dropping both helpers when a module has nothing but literals crossing.
It also sidesteps [`788`](788-wasm-import-multiple-memory-params-collide.md) for the
literal case -- though `788` still has to be fixed on its own terms.

**3. Shake the data section.**
`"TRIVIAL-GRAY-STREAMS"` is emitted into every module's data section and nothing in this
one references it. Data segments are not shaken with the code; after the tree-shaker runs,
a segment no surviving instruction addresses can go.

## Touch points

- `codegen/wasm/WasmStringRuntimeBuilder.java` (`buildStrToMemBody`, `buildCharvecToStrBody`)
- `codegen/wasm/WasmEmitHelper.java` (`emitCharvecToStrCall`, both overloads, and the
  `_str_from_data` path for item 2)
- `codegen/wasm/WasmLispCompiler.java` (`Ctx` flag, the scan that sets it, the data section)
- `codegen/wasm/WasmImportCompiler.java` / `WasmExportCompiler.java` (item 2's literal path)
- `.kb/wasm-gc-strings.md`, `.kb/string-accumulate-cost.md`
- `size-report/programs/` -- this program, or one like it, is the row that would track the win
