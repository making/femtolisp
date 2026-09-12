# The code section an external optimizer still halves

**Status:** open, items 1 and 2 LANDED. Re-measured 2026-09-12 on `e0bcf42f0`, after `790`
(`4531562b0`) moved the baseline: the program below is **1,808 bytes**, not the 1,090 of
the spike this item was written against. What is left is item 3 alone, and the measurement
below RESIZES it.

Difficulty: High (what is left is a post-emit code-section pass over a 19-33% residue, not
the "~114 bytes of ordinary tidying" the original text estimated). High for SCALE, not for
uncertainty: the ranking below says the shape is known.

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

### WHICH passes: measured 2026-09-13, so this step is done

`-Oz` is a pipeline of dozens of passes and the table above says how much, not which. Each
pass run ALONE over the shaken output (binaryen 130, `--enable-gc --enable-reference-types
--enable-exception-handling`), against the same module's own size:

| Program | residue | `inlining-optimizing` alone | share |
| --- | ---: | ---: | ---: |
| `zlib` `--no-wasi` | 18,788 | **13,333** | **71%** |
| `pi_approx` `--no-wasi` | 511 | **461** | **90%** |
| the measurement program | 163 | **124** | **76%** |
| `hello_world` `--no-wasi` | 148 | **132** | **89%** |

Everything else on `zlib`, in order: `coalesce-locals` 2,178, `simplify-locals` 2,146,
`merge-similar-functions` 213, `remove-unused-brs` 141. That is 18,011 of the 18,788 --
**96% of the residue is five passes, and one of them is three quarters of it.**

**The trap, and it is the first thing a reader of the old text would hit**: `inlining`
alone makes `zlib` 15,211 bytes BIGGER. Inlining leaves debris that only the following
optimization and DCE clear, which is why the entry above is `inlining-optimizing` and not
`inlining` -- the deliverable is ONE transform, "inline and then clean up after it", not an
inliner. The same shape rules out starting anywhere else: run alone, every other pass grows
the module (`code-folding` +1,260, `dce` +1,445, `duplicate-function-elimination` +1,449,
`remove-unused-module-elements` +1,449, `merge-blocks` +1,450, `heap2local` +1,468).

So the target list is three long -- inlining-with-cleanup, then local coalescing, then
local simplification -- and the first is where the bytes are. `WasmBodyFolder` already
merges identical bodies, and `merge-similar-functions`' 213 bytes says there is little left
there.

**What to do next, in order.**

1. Decide the shape: a post-emit pass beside the shaker in `am.ik.wasm`
   (language-independent by construction, and the module already parses there), an AST-level
   pass in `compiler` -- where rontolisp still has types and names, and where an inliner may
   be a great deal easier than over bytes -- or emitter changes at the sites that produce
   the one-call-site bodies in the first place. The ranking does not decide this; it only
   says what the pass has to do.
2. Whatever lands, measure it on `size-report/programs/` -- `zlib` is what decides whether
   it earns its maintenance -- and on `WasmTreeShakerCorpusTest`'s `wasm-tools validate`
   sweep, which is the only cheap guard against a rewrite that validates on the toy and not
   on the corpus.

### How the original estimate went wrong, so the next measurement does not

The "~114 bytes" came from running `-Oz` on a **hand-spiked** module -- the charvec call
cut out unconditionally and arithmetic forced to i31-only, to measure what `789` and `790`
were worth before either existed. That module was 567 bytes of code in 17 functions: there
was almost nothing left for an inliner to work on, so the residue measured small. The spike
was shrinking the module and shrinking the RESIDUE at the same time, and only the first was
the thing being measured.

**A residue is a property of the real output, never of a spike.** A spike answers "what is
this change worth"; it cannot answer "what is left afterwards", because it is not the
artifact that will be left. The other half of the same mistake was putting a number
measured on one micro program in this item's title while the body said to scope it against
the corpus -- the body was right.

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
