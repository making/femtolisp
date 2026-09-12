# The `:string` boundary drags 1,961 bytes of charvec normalization into every module

**Status:** open, item 1 LANDED. Measured 2026-09-12; re-measured the same day after
`788` landed (`6914db792`), which added 60 bytes to this program's two-`:string`
import wrapper and left every other section byte-identical. The numbers below are the
post-788 ones, i.e. the state BEFORE item 1 -- with item 1 in, the program below is
**2,725 bytes** and `.kb/wasm-gc-strings.md` carries the new table.

Difficulty: Medium (what is left is item 2 alone, and the recommendation is to CLOSE it
-- read the measurement under "Item 2" before implementing anything)

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

`rontolisp bench.lisp -o bench.wasm --no-wasi --optimize=size` = **4,623 bytes**, of which
the program's own code is **269** (`fib` 54, `init-app` 32, the two import wrappers 154, the
two export wrappers 21, `_initialize` 7). Sections: code 4,019 / 40 functions, types 210,
data 117, globals 79, exports 92, imports 32, functions 41, memory 3.

Cut the program down to isolate each half (same flags):

| Program | Bytes | Over the empty module |
| --- | ---: | ---: |
| one `(defun noop () nil)` + one export | 321 | -- |
| + one `:string` host import and a literal | 2,578 | 2,257 |
| + `fib` only (no strings at all) | 2,281 | 1,960 |
| both (the program above) | 4,623 | 4,302 |

So the module is 5.8% program and 94.2% runtime, and the runtime splits almost exactly in
two: the string boundary and the arithmetic. This item is the string half; `790` is the
other.

Two spikes, both measured on the program above (patches reverted; reproduce by editing the
two call sites named below):

| Build | Bytes | `wasm-opt -Oz` | gzip |
| --- | ---: | ---: | ---: |
| today | 4,623 | 2,561 | -- |
| this item's spike (drop the `_str_to_mem` preamble) | 2,721 | -- | -- |
| plus `790`'s spike (i31-only `+`/`-`/compare, i31 export return) | 1,090 | 869 | 728 |

Both spiked modules still run correctly under a Node host (`_initialize`, `InitApp`,
`RunComputation(20)` = 6765).

The `wasm-opt` column is a PROBE, not a proposed build step -- it was run to size the
opportunity before the spikes, and it mostly finds the same dead code this item and `790`
remove for reasons. Note the last row: once both land, an external optimizer has 221 bytes
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
| (a dead package-designator data segment) | 22 | see item 3 |

The same call is inserted after the string operand of every string consumer (`char`,
`subseq`, `string=`, the case/trim/concat family, `write-string`, `read-from-string`,
`intern`, `make-symbol`) and at the entry of `_equal`/`_hash`/`_print_val`/`_princ_val`.
One `:string` parameter anywhere is enough to root the whole group.

## Item 1 -- DONE

`Ctx.charvecPossible` gates every insertion point at once (the nine fixed runtime bodies
take it as a parameter, so no body can root the group behind the gate's back). Landed
numbers, mechanism and pins: **`.kb/wasm-gc-strings.md`, "The normalization is GATED on a
charvec being possible at all"**. In short: the program above 4,623 -> **2,725** (-41%),
`examples/browser/webgl-triangle` 4,431 -> **2,487** (-44%), and every program that does
anything else with a string byte-identical.

**The finding that changed the plan.** This item said to derive the gate from the
CONSTRUCTORS and not from the consumers. That is the right instinct and the wrong
analysis: the constructors are introduced by **Pass 2 lowerings the source never spells**.
A gate built from the constructor names (`make-array`/`make-string`, `subseq`/`copy-seq`,
`vector-push-extend`, the `with-output-to-string` family) compiles
`(write-string "hello" t :start 1 :end 3)` and `(format t "~:d" 1000000)` with the
normalization OFF, and both reach `_subseq_str` through the injected `%subseq-runtime`
anyway -- measured as `wasm trap: cast failure` at the un-normalized consumer, which is
the silent-wrong-answer class this item warned about, produced by the analysis it
proposed. What shipped instead is an ALLOWLIST (`CHARVEC_FREE_OPERATORS`): the gate closes
only for a program whose every operator provably cannot answer a string it was not handed,
so the only strings it holds are LITERALS -- and a literal is never a character vector.
An operator the list has never heard of OPENS the gate, each constructor asserts the flag
at compile time, and `-Drontolisp.debug.charvecgate=true` names the operator holding it
open. Widening the list is the way to widen the win, one operator at a time, each with its
lowering read.

## Item 3 -- ANSWERED, nothing to do here

The dead `"TRIVIAL-GARBAGE"` package-designator segment was reachable ONLY from the
normalization group, so item 1 took it: data 117 -> 94 on the program above. The general
mechanism this item asked for already exists -- `WasmTreeShaker` drops a whole segment
whose owning functions are gone AND excises sub-ranges no live `i32.const` lands in
(`DroppableDataRange`, `StringTable.shakeableRanges`). What survives is a range kept by a
constant that is not a citation of it, and that residue is
[`791`](791-module-level-slack-globals-types-data-hooks.md)'s row, not this one's.

## Item 2 -- measured, and the recommendation is NOT to do it

`(host-log "module initialized")` builds a runtime string from the data segment
(`_str_build`, 68 bytes) and copies it back into linear memory byte by byte
(`_str_to_mem`, 114 bytes) with its frame quotes, which the boundary strips again with
`+1`/`-2`. The bytes are already in the data segment at compile time, so a literal could
pass its own `(ptr, len)`. **The ceiling is those two helpers, ~182 bytes of the 2,725
that are left, and only for a module where NOTHING but literals crosses.** Against that:

- **It cannot be done in the wrapper.** A `wasm-import` is a synthetic defun called with
  BOXED values (`WasmFunctionCallCompiler.compileDirectCall`), so the wrapper never sees
  the literal; only the CALL SITE does. A literal path is therefore a second, specialised
  wrapper (or an inlined host call at the site), and the generic wrapper has to stay for
  `funcall`/`#'name`/`eval`. Inlining at the site also needs the import ORDINAL, which
  `importSlotIndex` only assigns after the user bodies are compiled -- hoistable, but it
  is a reordering of the compile, not a local change.
- **It makes an unenforced contract load-bearing, silently.** The ABI says the host must
  READ its memory-typed arguments before it answers ([`.kb/wasm-import.md`](../.kb/wasm-import.md)).
  A host that writes through the pointer today scribbles on throwaway scratch; pointed at
  the data segment it would permanently corrupt the literal -- and `StringTable.addString`
  DEDUPLICATES, so one bad write lands on every other use of that spelling, interned
  symbol NAMES included, for the life of the instance. That is a worse failure than the
  182 bytes are worth, and it arrives through someone else's bug.
- 788's per-parameter staging is the smaller constraint, but real: a literal occupies no
  region, so it must be skipped by the staging AND by the slot/free accounting
  (`emitStagedMemoryParam`, the `memParamBase` run, and `emitParkFrees`, which would
  otherwise hand `_park_free` a data-segment pointer and link neighbouring literal bytes
  into the park free list).

**If it is ever done**, the shape that avoids the hazard is a RUNTIME test rather than a
compile-time one: `_str_to_mem`'s caller can notice that a string's `id` is its own
data-segment offset (`_str_build` sets `id = off`, and `linear[id + i] == array[i]` by
construction) and skip the copy. That keeps one code path, keeps the host pointed at a
region the module owns per call, and costs a compare -- but it does NOT drop either
helper, so it buys speed, not bytes. Ask for the measurement before assuming otherwise.

## Touch points

- `codegen/wasm/WasmLispCompiler.java` (`CHARVEC_FREE_OPERATORS`, `charvecFreeProgram`,
  `Ctx.charvecPossible`) and `codegen/wasm/WasmEmitHelper.java`
  (`emitCharvecToStrCall`, `requireCharvecPossible`, `requireNoCharvecHelper`) -- item 1,
  landed
- `codegen/wasm/WasmImportCompiler.java` / `WasmFunctionCallCompiler.java` -- item 2's
  literal path, if the recommendation above is overruled
- `.kb/wasm-gc-strings.md` -- the gate and its numbers
