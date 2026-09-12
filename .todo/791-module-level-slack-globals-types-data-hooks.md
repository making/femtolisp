# The shaker leaves globals, types and data behind, and 214 bytes of tidying after them

**Status:** open. Measured 2026-09-12.

Difficulty: Medium

Third of the four items on the measurement in
[`789`](789-string-boundary-drags-in-the-charvec-normalizer.md); the program and flags are
there.

## The finding

**This item is entirely in-tree work. An external optimizer was run ONCE, as a probe, to
find out how much is here before deciding whether it was worth writing -- the answer is 214
bytes, and nothing about the plan below depends on that tool.** The core libraries take no
external dependency and this pass would not be the first one.

| Module | rontolisp | what an external `-Oz` still finds |
| --- | ---: | ---: |
| the measurement program, today | 4,563 | -2,055 |
| the same with `789` + `790` spiked in | 1,030 | **-214** |

Read the two rows together: on today's module an optimizer looks impressive (40 functions
to 20, 15 globals to 1) because it is deleting the SAME dead runtime that `789` and `790`
delete at the source. Once those land it has 214 bytes left -- and that residue is this
item, because most of it is not clever.

Where the 214 sits (the 1,030-byte module, as emitted vs. the probe):

| Section | as emitted | probe | Worth | Note |
| --- | ---: | ---: | ---: | --- |
| globals | 79 / 15 | 6 / 1 | 73 | **14 dead globals survive the shaker** |
| code | 567 / 17 fn | 460 / 9 fn | 107 | inline one-call-site bodies, drop unused locals |
| types | 116 / 17 | 96 / 12 | 20 | rec groups outlive their last user |
| data | 92 / 6 | 86 | 6 | a segment nothing addresses |
| exports | 92 / 6 | 92 | 0 | the probe cannot know; item 2 below is 65 more |

Three of the five rows are deletions the shaker simply does not attempt, not optimizations.

## What to do

**1. Shake globals, types and data segments, not just functions.**
`WasmTreeShaker` walks functions. A global nothing reads, a type nothing references and a
data segment no instruction addresses all survive it. In a 1 KB module the globals alone
are 7.7% of the file. This is the cheapest item here and the one with no design question
in it.

**2. Stop exporting the two `--no-wasi` host hooks unconditionally.**
`__ronto_seed_random` and `__ronto_set_time` are emitted on every `--no-wasi` core module
whether or not the program can reach `random`, `get-universal-time`,
`get-internal-real-time` or `get-internal-run-time`. Their two bodies, two function-section
entries, and their names in the export section are about **65 bytes** -- 6% of a 1 KB
module. The build already computes exactly the reachability this needs:
`compiler/NoWasiLoadPathRefusals` walks the program to decide which of these primitives a
module can reach, and prints a host obligation when it can. Emit each hook when its
primitive is reachable, keep emitting both when the answer is unknown, and say so in
`.kb/wasm-export-no-wasi.md` next to the existing "an EXPORT, not an import" reasoning --
the hook's contract (call it before `_initialize`) is unchanged, it just stops appearing on
modules that have nothing to seed.

**3. A cleanup pass in `am.ik.wasm`, written here.**
The ~107 bytes left in the code section are ordinary post-emit tidying: inline a body with
one call site, merge identical bodies (the AST path already folds duplicates -- see
`.kb/optimize-dead-code-elimination.md` -- but nothing does it after emission), drop unused
locals, fold `local.set`/`local.get` pairs. Each is a local rewrite over a function body
the module already parses; none of it needs a general optimizer framework, and it belongs
beside the shaker where it is language-independent by construction. Scope it against the
`size-report` corpus rather than this one program -- the win on `zlib` is what decides
whether it earns its maintenance -- and stop at the point where the next transform starts
needing a dataflow framework: that is the line between this item and a project nobody
asked for.

## Also seen, not size

`_initialize` on a module with no top-level forms exists only to pre-grow the GC heap: one
dropped 16 MiB byte array (`.kb/wasm-gc-heap-pregrow.md`). For a resident module a host
instantiates to call a few exports on demand, 16 MiB of allocation churn at instantiation
is a knob worth a measurement of its own -- the floor was drawn from programs that run to
completion, not from ones that answer calls. Not part of this item; file it separately if
the measurement says anything.

## Touch points

- `am/ik/wasm/WasmTreeShaker.java` (globals, types, data), and the new pass beside it
- `codegen/wasm/WasmLispCompiler.java` (the hook emission block, the data section)
- `compiler/NoWasiLoadPathRefusals.java` (the reachability the hooks should read)
- `.kb/wasm-export-no-wasi.md`, `.kb/optimize-dead-code-elimination.md`
