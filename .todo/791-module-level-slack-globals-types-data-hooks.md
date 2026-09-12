# The emitter leaves ~45% of a small module on the floor after the tree-shaker

**Status:** open. Measured 2026-09-12.

Difficulty: Medium

Third of the four items on the measurement in
[`789`](789-string-boundary-drags-in-the-charvec-normalizer.md); the program and flags are
there.

## The finding

`wasm-opt -Oz` over the module the backend already considers finished:

| Module | rontolisp | after `wasm-opt -Oz` |
| --- | ---: | ---: |
| the measurement program, today | 4,563 | 2,508 |
| the same with `789` + `790` spiked in | 1,030 | 816 |

40 functions become 20; 15 globals become 1. No source change, no flag, no semantic
difference -- the modules still run. This is not an argument for shipping a binaryen
dependency (the core libraries take none, and `am.ik.wasm` is the language-independent
half): it is a measurement of how much the emitter leaves behind AFTER
`WasmTreeShaker.shake` has run, i.e. what a native cleanup pass in `am.ik.wasm` is worth.

Where it sits in a module that is already small (the 1,030-byte one, sections as emitted
vs. after `-Oz`):

| Section | rontolisp | `-Oz` | Note |
| --- | ---: | ---: | --- |
| code | 567 / 17 fn | 460 / 9 fn | inlining + local cleanup |
| globals | 79 / 15 | 6 / 1 | **14 dead globals survive the shaker** |
| types | 116 / 17 | 96 / 12 | rec groups outlive their last user |
| exports | 92 / 6 | 92 | see below |
| data | 92 / 6 | 86 | |

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

**3. A cleanup pass in `am.ik.wasm`.**
What `-Oz` recovers beyond items 1 and 2 is ordinary post-emit tidying: inline a function
with one call site, merge identical bodies (the code path already folds duplicate bodies at
the AST level -- see `.kb/optimize-dead-code-elimination.md` -- but not after emission),
drop unused locals, fold `local.set`/`local.get` pairs. It belongs beside the shaker, is
language-independent by construction, and pays on every program rather than on small ones
only. Scope it against the `size-report` corpus, not against this one program: the win on
`zlib` decides whether it is worth the maintenance.

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
