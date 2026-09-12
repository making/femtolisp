# The code section an external optimizer still halves

**Status:** open, items 1 and 2 LANDED. Re-measured 2026-09-12 on `e0bcf42f0`, after `790`
(`4531562b0`) moved the baseline: the program below is **1,808 bytes**, not the 1,090 of
the spike this item was written against. What is left is item 3 alone, and the measurement
below RESIZES it.

Difficulty: High (what is left is a post-emit code-section pass over a 19-33% residue, not
the "~114 bytes of ordinary tidying" the original text estimated)

Third of the four items on the measurement in
[`789`](789-string-boundary-drags-in-the-charvec-normalizer.md); the program and flags are
there.

## Items 1 and 2 -- DONE

Landed numbers, mechanism and pins:
**`.kb/optimize-dead-code-elimination.md`, "Global section" and "Host cell hooks"**, and
**`.kb/wasm-export-no-wasi.md`, "Both hooks are DROPPED from a module whose program cannot
use them"**. In short:

- **Globals, and the types that die with them.** `WasmTreeShaker` walked functions, types
  and data; a global nothing read survived it, one per top-level Lisp variable, so the
  runtime's own specials travelled into every module (15 globals, 79 bytes, on a 1.8 KB
  reactor that read none). `global.get`/`global.set` became a renumbered ref kind, imported
  and exported globals are roots, initializers are edges. The type section wins too: a dead
  global's initializer can be a `rec` group's last citation (`(print 1)` loses 34 bytes of
  types that way).
- **The two `--no-wasi` host hooks.** `__ronto_seed_random` / `__ronto_set_time` are
  exports, hence roots, hence immortal -- on a module that draws no random number and reads
  no clock, 65 bytes writing cells nothing reads. `WasmTreeShaker.HostCellHook` decides it
  by OBSERVATION rather than by name: a hook is kept when some function surviving the shake
  WITHOUT it holds an `i32.const` equal to the cell address.

**The finding that changed the plan for item 2.** This item said to read the reachability
off `compiler/NoWasiLoadPathRefusals`. That class walks the LOAD path -- the top-level
forms and what they call -- which is strictly narrower than the question: a program whose
only `(random ...)` is inside a `wasm-export` would have lost the hook it needs. Nor is a
call graph the answer, because nothing CALLS a setter and `random` is inlined at the draw
site (`.kb/random.md`), so there is no reader function to ask about. The cell is the whole
channel, so the citation of the cell is the test.

| Program | before | after | delta |
| --- | ---: | ---: | ---: |
| the measurement program, `--no-wasi` | 1,808 | **1,658** | -8.3% |
| `examples/browser/webgl-triangle` | 1,830 | **1,683** | -8.0% |
| `hello_world` `--no-wasi` / WASI | 643 / 590 | **493 / 509** | -23.3% / -13.7% |
| `pi_approx` `--no-wasi` / WASI | 1,688 / 1,635 | **1,528 / 1,544** | -9.5% / -5.6% |
| `zlib` `--no-wasi` / WASI | 94,172 / 94,167 | **94,069 / 94,099** | -0.1% |

It is a FLOOR effect: a fixed 80-150 bytes every module paid, a quarter of `hello_world`
and a rounding error on `zlib`. Sixty-five of it is below what an external optimizer can
reach at all -- the hooks are exports, and an export is a root for binaryen too.

## Item 3 -- open, and bigger than this item said

**What the re-measurement showed.** `wasm-opt -Oz --all-features` (binaryen 132) over the
SHAKEN output, as a probe:

| Program | rontolisp | then `-Oz` | residue |
| --- | ---: | ---: | ---: |
| the measurement program, `--no-wasi` | 1,658 | 1,505 | 153 (9.2%) |
| `examples/browser/webgl-triangle` | 1,683 | 1,340 | 343 (20.4%) |
| `hello_world` `--no-wasi` / WASI | 493 / 509 | 349 / 391 | 144 / 118 (29% / 23%) |
| `pi_approx` `--no-wasi` / WASI | 1,528 / 1,544 | 1,022 / 1,064 | 506 / 480 (33% / 31%) |
| `zlib` `--no-wasi` / WASI | 94,069 / 94,099 | 75,517 / 75,887 | **18,552 / 18,212 (~19.5%)** |

The original text estimated this residue at ~114 bytes and called it "ordinary post-emit
tidying ... stop at the point where the next transform starts needing a dataflow
framework". That estimate was taken on ONE 1,090-byte spiked module before `790` landed.
On the corpus the line was supposed to be scoped against, it is **19-33% of every module**
and 18.5 KB on `zlib` -- so the line the item drew is in the wrong place: this is a sized
opportunity, not a cleanup, and it is worth its own design rather than a list of local
rewrites.

**What to do next, in order.**

1. **Find out WHAT the 18.5 KB on `zlib` is before writing a pass.** `-Oz` is a pipeline of
   dozens of passes; the number above says how much, not which. Run binaryen's passes
   individually (`--metrics`, and `-O` with single `--pass` runs) over `zlib.wasm` and
   `pi_approx.wasm` and rank them. The deliverable of that step is a table in
   `.kb/optimize-dead-code-elimination.md`: pass name, bytes, and whether the same win is
   already available at the AST level (where rontolisp has types and names) rather than
   post-emit. **Do not start with the local rewrites the original text listed** -- inline a
   one-call-site body, merge identical bodies, drop unused locals, fold `local.set`/`get`
   pairs -- until the ranking says they are where the bytes are. `WasmBodyFolder` already
   merges identical bodies, so at least one of them is spent.
2. Only then decide the shape: a post-emit pass beside the shaker in `am.ik.wasm`
   (language-independent by construction, and the module already parses there), an AST-level
   pass in `compiler`, or emitter changes at the sites the ranking names.
3. Whatever lands, measure it on `size-report/programs/` -- `zlib` is what decides whether
   it earns its maintenance -- and on `WasmTreeShakerCorpusTest`'s `wasm-tools validate`
   sweep, which is the only cheap guard against a rewrite that validates on the toy and not
   on the corpus.

## Also seen, not size

`_initialize` on a module with no top-level forms exists only to pre-grow the GC heap: one
dropped 16 MiB byte array (`.kb/wasm-gc-heap-pregrow.md`). For a resident module a host
instantiates to call a few exports on demand, 16 MiB of allocation churn at instantiation
is a knob worth a measurement of its own -- the floor was drawn from programs that run to
completion, not from ones that answer calls. Not part of this item; file it separately if
the measurement says anything.

## Touch points

- `am/ik/wasm/WasmTreeShaker.java`, `am/ik/wasm/WasmSections.java` -- items 1 and 2, landed
- `codegen/wasm/WasmLispCompiler.java` (`shakeCore` offers the two `HostCellHook`s)
- `.kb/optimize-dead-code-elimination.md`, `.kb/wasm-export-no-wasi.md` -- the numbers and
  the mechanics
