# 670. Run a published SLM checkpoint: what Hugging Face ships, loaded as shipped

Difficulty: High (the umbrella; the children are sized individually)

Filed 2026-09-03 from the re-verification of `.todo/482` (`bfloat16`), whose README "Round
2" is the measurement record; `.todo/482` stays the width half of this.

**The goal: a small language model that someone downloaded from Hugging Face runs on
rontolisp from the file they downloaded** -- no Python, no `export.py`, no conversion step
outside the language. `examples/llm` runs karpathy's `.bin`, a format one project writes;
the models people actually run are published as **safetensors** (bf16, one JSON header and
raw tensors) and **GGUF** (F32 / F16 / BF16 / Q8_0 / Q4_K_M, tokenizer and hyperparameters
in the same file). Checked 2026-09-03: SmolLM2-135M, TinyLlama-1.1B-Chat and Qwen2.5-0.5B
are each 100% BF16 in `model.safetensors`; no current small model is f16.

**This file is the plan and the rules. Every number it quotes has a child that owns it**
(rule 9) -- go there to change one.

## What the measurements decided, width by width

| width | verdict | where |
| --- | --- | --- |
| **bf16** | THE width. 1.5-2.1x f32 on one thread (Graal / C2), 1.6x on 20; widening exact; every checkpoint is in it | `.todo/482` (483-490) |
| **IEEE f16** | not a width -- a **load-time conversion** into `#f` / `#bf16`. A fused f16 GEMV is 0.30-0.58x on either JIT | `.todo/671` |
| **Q8_0** (32 int8 + a scale) | a **read-only weight matrix** type with an integer-dot GEMV: 2.0x f32 / 1.15x bf16 on one thread, 1.9x on 20, a quarter of f32's bytes | `.todo/672`, closed; follow-up `.todo/706` |
| **Q4_0 / Q4_K** | not a CPU item: the nibble unpack is ALU-bound at 5.7 GB/s (1.1x f32 for 8.5% error). A device width | `.todo/490` |

Two facts under all four: **the width is bandwidth, not fitting** -- 4.4 GB of f32 fits an
8 GB laptop -- and **every kernel number is JIT-dependent**: the spike's fused kernel fell
to 0.20x under C2 from an inlining cliff, so `.todo/488` takes its numbers under both JITs.

## Children, and the order

| item | what | difficulty | state |
| --- | --- | --- | --- |
| `671` | f16 / bf16 **bits** widened in bulk into an existing width, every backend | Low | **closed** 09-03 |
| `673` | read a GGUF: metadata, tensor table, F32 / F16 / BF16 / Q8_0, tokenizer fields | Medium | **closed** 09-03 |
| `674` | the byte-level BPE tokenizer from GGUF fields or `tokenizer.json` | Medium | **closed** 09-03 |
| `676` | the forward pass as a table of layer kinds: QK-norm, NoPE, gates, partial RoPE | Medium | **closed** 09-03 |
| `678` | the LFM2 gated short-conv layer: LFM2.5-1.2B-Instruct | Medium | **closed** 09-05, byte-identical to `llama.cpp` |
| `672` | the Q8_0 weight matrix and its integer-dot `vec:matvec` | High | **closed** 09-05; one-thread follow-up is `.todo/706` |
| `675` | read a safetensors file (+ `config.json`) | Medium | **closed** 09-06; the reader's `#bf16` destination is now pinned across the two engines that have the width (`cli/SafetensorsBfloat16CompilePathTest`) |
| `677` | the Gated DeltaNet layer: Qwen3.5-0.8B, and every Qwen 3.5-3.8 dense model | High | **closed** 09-06: the layer and its pin (`examples/llm/deltanet.lisp`, `deltanet-check.lisp` on all four backends with and without `--simd`) landed 09-03; its "Remaining" -- the bf16 and quiet-box `tok/s` rows -- was `489`'s lane's work and is in `examples/llm/README.md`. Re-verified at the close on `40a80f91`: one 64-token answer from safetensors and GGUF at f32 and bf16, 1 and 32 threads (eight runs, token for token) |
| `489` | the model rungs: TinyLlama / SmolLM2, Qwen3-0.6B, LFM2.5-1.2B, Qwen3.5-0.8B | **closed 09-06** | f32 and bf16 measured on six models 09-05; result and reading now in `examples/llm/README.md` and `.todo/history/2026-09.md`. The fused pairing is bf16 weights against f32 activations only, every other pairing declining to the scalar defun (`.todo/696`) |
| `490` | bf16 on the device | High | not started; GB10 only |

**Order: 671 -> 673 / 675 -> 674 -> 489 rung 0 at f32 -> 676 -> 678 -> 677 -> 487 -> 489 at
bf16 -> 672 -> 490.** The point of it: 671 needs no new array type, so a BF16 checkpoint
loads into `#f` BEFORE the bf16 width exists and the readers are debugged at f32 with the
kernels out of the picture.

## What runs today

**A published checkpoint runs, in three formats, no Python and no conversion step.**
Qwen3.5-0.8B from its BF16 safetensors AND from ggml-org's BF16 GGUF, **token for token
identical between the two**; TinyLlama-1.1B-Chat from safetensors and an F16 GGUF, same
forty tokens; stories15M converted to GGUF answers with `run.c`'s own text token for token
-- the one EXTERNAL oracle, and the one that caught a live bug.

**The parallel leg is bound by the parallel machinery, not by DRAM.** Distribution,
barriers and per-row dispatch, and **how much a model pays depends on how its work is cut
up**: Qwen3.5's Gated DeltaNet does 576 small 128x128 GEMVs per token, LFM2.5 does ~30 big
matvecs, so the one paying more dispatch peaks earlier. The signature is a model-specific
SATURATION POINT, carried by within-model scaling only -- tok/s over tok/s, no byte
estimate anywhere.

Three independent routes reach it:

1. **The knee did not move when the bytes halved** (`489`, six models, two widths) -- flat
   by 16 threads in both arms for Qwen3.5, TinyLlama and both SmolLM2s, still climbing at
   32 in both for LFM2.5. A DRAM cap would have pushed the knee outward. **This leg is
   byte-estimate-free and is the strongest.**
2. Dorian's knee at f32 alone: 1 -> 32 threads is **4.37x** for LFM2.5 against **2.95x**
   for Qwen3.5.
3. ~~GB10 measuring 41-42 Gelem/s at BOTH 4.2 MB and 67 MB of weights~~ -- **withdrawn
   2026-09-06 by `.todo/702`.** There was no plateau to explain: the f32 parallel arm's
   rate over shape is a HUMP, and the two cells that agreed were two points on it. Route 3
   is not evidence for anything and is kept here only so a reader who met it elsewhere
   knows it was retired.

A cross-model GB/s comparison is NOT among them: it divides by an activation-blind
parameter-count estimate that omits exactly Qwen3.5's recurrent state.

**The clean discriminator RAN, 2026-09-06** -- a parallel f32 GEMV size sweep from 256x256
(0.26 MB, unambiguously cache-resident) to 4096x4096 (67 MB), no model in it at all, on a
verified-quiet GB10. Neither of the two answers it was built to choose between:

- **256x256: 13.1 Gelem/s, 0.89x its own SERIAL rate.** Machinery, unambiguously -- 20
  threads get eight leaves at that row count, so most of them get no work.
- **1024x1024 to 2048x2048: 48-69 Gelem/s**, well above the retired "ceiling".
- **4096x4096: 41-44**, and 3072x3072 dips to 25-28 for reasons nobody has explained yet
  (`.todo/713`).

So the cap is real but it is not one number and not one mechanism: below ~500 rows the
work is not cut finely enough to fill the box, above ~30 MB the bytes bind, and in between
the kernel runs at 1.5x what was being quoted as the box's limit. Record and the
leaf/grain arithmetic:
`.todo/artefacts/702-the-parallel-cap-is-the-machinery-or-memory-one-run-decides/README.md`.
**Route 1 is untouched by this and is still the strongest leg** -- it compares a model
against itself across a width change and never divides by a byte estimate.

Carry one consequence: **a parallel GEMV rate is a property of how the work was cut up**,
not of the machine and not of the weights. The 10x collapse seen while two lanes shared a
box is `.todo/697`'s mechanism, not a property of anything measured here.

## The two machines, because every number here is one of them

- **`dorian`** -- Xeon E5-2697A v4, Broadwell x86-64, 64 threads, 251 GB, GraalVM 25.0.4,
  AVX2 256-bit, **no avx512**. Orchestrator A's box; no GPU.
- **GB10** -- aarch64 Cortex-X925, 20 cores, 121 GB, NEON 128-bit, CUDA. Orchestrator B's
  box, and the only one that can run the GPU legs.

A measurement without its base commit, JIT, machine and load average is not comparable to
another; a quiet window is per-box and each side takes its own.

**Checkpoints, per box.** On **dorian**, `/home/administrator/models/`: `qwen35/`
(Qwen/Qwen3.5-0.8B: `config.json`, `model.safetensors.index.json`,
`model.safetensors-00001-of-00001.safetensors` 1746942600 bytes sha256
`04b1c301231dd422b8860db31311ab2721511346a32cb1e079c4c4e5f1fe4696`, `tokenizer.json`,
`tokenizer_config.json`), `qwen35-gguf/` (ggml-org: `Qwen3.5-0.8B-BF16.gguf` 1557662496
`9a7bed4041b7975e0f71fa34670d1e9025213bc92905ac0db75d36c4fa3fa623`, `Qwen3.5-0.8B-Q8_0.gguf`
833592096 `37ae482d336108d23516fa35e8e0c4126688d81018b87178a18d752a1357814f`), and beside
them `lfm25/`, `lfm25-gguf/`, `qwen3-0.6b/`, `qwen3-0.6b-gguf/`, `smollm2-135m/`,
`smollm2-135m-instruct/`, `smollm2-360m-instruct/`, `tinyllama/`, the three `stories15M` /
`smollm2-135m-f16` GGUFs, and `tools/` (`llama.cpp`); the `677` block that used to name
them was stale by a directory move (rule 9, verified 09-06 by the digests). On **GB10**,
`/home/maki/models/qwen35-gguf/` and `qwen35-hf/`, hash-verified 09-05; the HF cache holds
only `unsloth/Qwen3.8-Flash-Next-GGUF`, so any other GB10 lane re-fetches. None of it
belongs in the repo, and `examples/llm/.gitignore` is what keeps the two `stories15M`
artefacts out -- see rule 12.

## The certification record

**Head certified: `ff903aa8`.** dorian full suite green there: 10077 / 0 / 0 / 276 skipped
with **234** reports. The report count moved from `0e65326b`'s 232 by exactly the two test
classes A's lane added -- `cli/LlmChatModeWithoutTemplateTest` (A-2) and
`cli/SafetensorsBfloat16CompilePathTest` (A-3). GB10 has NOT run this head; the previous
certification `0e65326b` was GB10's (10066 / 0 / 0, 232 reports), and `d4225aa5` before it
was the last one both boxes held. **Totals are now comparable across boxes** -- 708 landed
2026-09-06 and the corpus stopped counting other agents' worktrees.
Rule 8 decides when a certification lapses.

**What a run certifies is failures, errors and the REPORT-FILE COUNT -- never the totals.**
A differing report count means a class was DROPPED rather than skipped, which no skip
accounting reveals.

Three things a reader needs before comparing any two runs. 708 closed 2026-09-06, so its
full account is recoverable only as
`git show 97c85518~:.todo/708-the-formatter-corpus-walks-agent-worktrees.md`; what survives
it lives here:

1. **Totals ARE comparable across boxes as of `0e65326b`.** `LispFormatterTest` walked
   `Path.of(".")` and formatted every `.lisp` under `.claude/worktrees/`, so one term of
   the comparison used to be how many agents had run on that box recently. The walk now
   excludes `/.claude/` and `repositoryCorpusStaysWithinASmallFactorOfTrackedSources` pins
   it. **A total taken before `0e65326b` is still not comparable to one taken after.**
2. **Skips ARE comparable**, and the 09-03 accounting of them was wrong by SELECTION, not
   arithmetic: diffing both complete censuses gives seventeen differing classes netting
   exactly 87. Contamination does not reach skips.
3. **A skip count is only a signal against a prior skip count for the same slice.** Its
   designed meaning and its defect meaning are the same integer. `Tests run` is invariant
   under skipping and not under deletion, so a skipped leg keeps the headline total while
   removing the coverage -- which is how `682` came to be accepted by a run that skipped
   the part of the suite the rename was most likely to break.

Twice on 09-05 coverage fell into the SEAM between two correct plans -- nobody skipped an
assigned step, and the combination was what nothing covered. **A verification owed by one
party and skipped by everyone else is a gap that looks exactly like coverage until someone
checks who actually ran it.** `.todo/709` Part 2 keeps that kind separate from record
failures.

## Findings from the run, and where each one now lives

Pointers, not records -- the home is where it gets updated.

- **`483`'s rule is stated wrong in 483**: not "never write a `default`" but **"an arm
  matching two or more permits IS a default, whatever it is spelled"**. In `.kb/vec.md`.
- **`%la-gather-strided` has five readers** and grepping the name finds two. `.todo/687`.
- **Seven sites hand-write the bf16 conversion arithmetic** and only
  `am.ik.rontolisp.BFloat16` is the authority. Census: `.todo/487`'s remainder.
- **`.kb/string-index-cost.md`** records what `690`'s 340x is and is not.

## Lanes for the week of 2026-09-15: ONE worker per orchestrator

The two-worker arrangement ran 2026-09-08 to 09-05 and is over. **From here each
orchestrator drives ONE lane at a time, serialized: an item completes, is committed and
pushed, and only then does the next start.** What that buys is the thing two lanes cost --
the surface-accounting overhead in `.todo/709` exists entirely because two lanes on one box
can touch one mechanism without either seeing the other.

Model by difficulty, `effort=high` throughout: **High -> Fable, Medium -> Opus, Low ->
Sonnet.** A dead worker is RESUMED, never respawned.

**Orchestrator A's lane is COMPLETE as of 2026-09-06**: all five items closed, committed and
pushed one at a time, and the suite run on `develop` at `ff903aa8` above. Two of the five
(A-3, A-4) had nothing left to BUILD -- both were remainders that other lanes had already
satisfied, and finding that out was the work. Nothing new was filed; the next turn's lane
design starts from the unassigned pool.

**Orchestrator A -- dorian, the model side, no GPU.** `489`'s bf16 rungs finished 09-05:
six models at both widths, the reading beside the prediction in `489`, the summary in
`examples/llm/README.md`. In order:

| # | item | difficulty | why here, why now |
| --- | --- | --- | --- |
| A-1 | close `489` | Low | **done 09-06**: closed with the child table, `482`'s row and the rule-11 capability sweep, which found nothing gated on "a 1B-class model runs at bf16" that was not already 489 itself. Done criteria met; the lane deliberately left closing to lane design. It needs the child table above, the `675` / `677` cross-references, and rule 11's sweep for items waiting on the CAPABILITY ("a 1B-class model runs at bf16"), which no grep for the number finds |
| A-2 | `712` `-m chat` with no template answers a different question | Low | **done 09-06**: `-m chat` on a checkpoint with no template now signals and names `-m generate` instead of falling through, and the `tok/s` line counts SAMPLED tokens and is suppressed at zero -- which is the half that made the discarded runs look like results. `cli/LlmChatModeWithoutTemplateTest` pins it on the checked-in `stories260K`. Cost twelve discarded timed runs and left a suspect pair of rows on develop. A runs every future rung, so A pays again until it is fixed. One condition plus a failing test on the checked-in `stories260K` |
| A-3 | `675` read a safetensors checkpoint | Medium | **done 09-06**: nothing was left to BUILD -- `487` landed all five bullets of the `#bf16` target on 09-05 -- and what the audit found missing was a PIN. Every bf16 pin was one engine each (`make-array` with a runtime designator, the bulk `read-sequence`, and the reader itself interpreted only), so the reader COMPOSED of them could have diverged between `java -jar` and a compiled `.class` with nothing to catch it. `cli/SafetensorsBfloat16CompilePathTest` runs one program both ways over a three-dtype fixture and compares bit patterns. No lane code was written, so neither JIT cliff was in the path |
| A-4 | `677` the Gated DeltaNet layer | High | **done 09-06**: nothing was left to BUILD -- the two "Remaining" bullets were `489`'s rows (in the README since `b87aed25`) and `678`'s run (closed 09-05), so rule 3 sorts both as done-elsewhere, not blocked. The close re-ran the model on `40a80f91` (safetensors and GGUF, f32 and bf16, 1 and 32 threads: one text, eight runs; 1.87 -> 2.54-2.63 tok/s on one thread and 6.39 -> 8.11-8.61 on 32, idle loadavg 1.5 with the parallel rows over the previous run's decaying workers -- a ratio check, not a replacement for the README's quieter window) and the `deltanet` slice of `ExamplesE2eTest` (12 legs green). The dorian checkpoint inventory moved here with sizes and digests (rule 10) |
| A-5 | `711` what a directory rename breaks outside its own diff | Medium | **done 09-06**: the card is `.kb/directory-rename.md`, indexed from `.kb/README.md`. Every claim was re-measured against the tree rather than copied out of `708`, which is how the card's own closing paragraph earned its subject -- `708`'s "18 directory-local `.gitignore` files" is **17**, and the 18 counts the ROOT one, the single file the card does not apply to. Two mechanics `708` did not have: `git mv` on the DIRECTORY carries untracked files along, so the exposure needs a PER-FILE rename; and the residue reports as one collapsed `?? old/` line unless `git status --porcelain` is given `-uall` |

**`489` closed 09-06 (A-1)**: the child table above and `482`'s carry the closure; the
sweep found no item gated on the capability rather than the number (rule 11) and none of
the "blocked by 489" style dependency lines rule 1 asks about -- every other reference to
`489` in this tree is a data citation, which a closed item keeps.

**Orchestrator B -- GB10, the width chain and the device.** In order:

| # | item | difficulty | why here, why now |
| --- | --- | --- | --- |
| B-1 | `708` the formatter corpus walks `.claude/worktrees/` | Low | **done 09-06** (`0e65326b`): the walk excludes `/.claude/` and `repositoryCorpusStaysWithinASmallFactorOfTrackedSources` pins the corpus to a small factor of `git ls-files`. The standing caveat is lifted -- see the certification record above. Do #3 (25 stale worktrees) was cleanup and stays undone |
| B-2 | `702` is the parallel cap machinery or memory | Low | **done 09-06** (`070984ae`): neither, and there was no plateau -- the rate over shape is a hump. It cost route 3 above and left `.todo/713` (the 3072x3072 dip) behind |
| B-3 | `707` `coerce` / `concatenate` drop a packed FLOAT element type | Medium | **done 09-06**: both operators build the packed float array at all three widths through a shared `%seq-float-vector`, and the result-type normalizer now carries the `ArrayElementTypes` CODE instead of an integer width, so the packed families come from the closed space. bfloat16's refusal reaches the new path on wasm. Left behind: `.todo/714` (`(vector character)` is the one specialized code still answering a general vector) |
| B-4 | `710` a closed item's artefacts and an open item share one namespace | Medium | **done 09-06**: the link check went first and paid for itself before the rename -- `PathCitationTest` found five citations already broken on develop (three `examples/` paths in javadoc, an httpbin example that had moved under `examples/net/`, and a wasm-condition-catching card name that never existed -- `.kb/error-handling.md` is the file meant). The move then went further than the item asked: ALL sixteen artefact directories moved to `.todo/artefacts/`, open items included, so the recurring `git mv` per close is nil and the invariant is testable ("`.todo/` has no numbered directories") instead of resting on whoever closes an item remembering. The new mechanic it taught is in `.kb/directory-rename.md` 2b -- **a move that changes DEPTH rewrites the relative paths INSIDE what moved, and `git grep` for the old path finds none of them**. The duplicate is resolved: the ANSI ranked-gap item is `.todo/715` (later commit renumbers, per `.todo/.history.md`) |
| B-5 | `490` bf16 on the device | High | The last child of the width chain, and GB10 is the only box that can run it |
| B-6 | `706` the Q8_0 integer-dot GEMV is instruction-bound on one thread | High | Falls straight out of `672`'s closure and is a kernel item |

**Decisions to take at planning, before either lane starts:**

- **`.todo/709` is an explicit DRAFT and needs co-signing or cutting by both
  orchestrators.** It is process, so one side adopting it unilaterally is the failure it is
  written about.
- **The one-thread bf16 ratio** (1.10x-1.26x across six models against `489`'s "does not
  move much") is B's to take into `489` rather than to run as a lane.
- `711` deliberately does NOT carry the general reading disciplines -- diff the lists rather
  than reasoning about which terms ought to differ; a sum that closes is not evidence about
  its terms; relay a census from the file with its total AND its class count. Those are
  process: they belong to the `709` co-sign or nowhere.

**Unassigned pool, neither side's yet:** `693`, `694`, `683`, `684`, `686`, `687`, `689`,
`696`, `697`, `698`, `699`, `700`, `701`, `703`, `704`, `705`, `597`, `695`.

## Standing rules this run earned, in the order they cost the most

Cited by number from other items -- **the numbering is fixed.**

1. **Only the closer can write back a dependency.** Six items closed 09-03 and twelve open
   todos still read as blocked by them that afternoon. The grep for items naming the number
   belongs beside the history row in the close procedure.
2. **A count an item wrote down is not a completion test.** A stale dependency line delays a
   start; **a stale count fakes a finish.** Start an audit from the grep, never the number.
3. **Sort every "Remaining" into blocked / not-done / deferred.** Only the first is a real
   remainder; the second is unstarted work in a blocker's clothes; the third evaporates
   without an owner and a date. Two of nine were truly blocked.
4. **One session runs the full suite on `develop`, the other runs the GPU legs.** Three reds
   were invisible from every lane's own worktree.
5. **Never two device-touching runs at once, separately from who owns what.** `./mvnw test`
   includes `GpuTest`, so a full suite IS device-touching. **Ownership says who takes a
   result; exclusion says what may run at once** -- fusing the two produced a
   self-contradictory instruction to one lane.
6. **A suite can hold a defect invisibly while every case sits on one side of its
   condition, and the half that looks more exhaustive is the half that hides it.** Three in
   one day, including `692` against a `671` that closed claiming all four backends while its
   tests counted backends and never `--simd` on each (`.todo/694`).
7. **A rule one lane derives from one measurement is a hypothesis until the other lane has
   tried to break it.** Three corrections in one day, each of which would otherwise have
   entered `.kb` as a law. What survives from the first: a failure count's SIZE narrows the
   SEARCH, never the VERDICT (`.kb/measurement-probes.md`). **Say it to the other lane
   before writing it into `.kb`.**
8. **A run certifies a head it did not run against when the FILE SET says so, never the
   elapsed time.** `git diff --stat <ran-at> <head> -- src/` empty means a re-run would only
   re-measure `.todo/` edits. One command, and it is the whole argument.
9. **An umbrella's status paragraph is evidence only where no child covers the same fact.**
   Where a child does, the child wins and the umbrella POINTS. This file once said "the
   checkpoints are gone" while `.todo/677` carried the correct paths, and two lanes were
   sent to re-download 12 GB that was on disk. **A restated fact also decays PER BOX**, and
   that direction is worse: it skips a needed re-fetch rather than repeating an unneeded
   one.
10. **Record a checkpoint's SIZE and sha256 beside its path**, because provenance is
    recoverable from the file but not the file from the provenance. Hugging Face answers
    `/api/models/<id>?blobs=true` with the LFS sha256, so a checkpoint whose repo path was
    lost is re-identified by matching bytes already held. **The digest and the refusal to
    guess are two independent goods**: refusing to guess is why an id is VERIFIED, the
    digest is why a guess would have been SURVIVABLE. What a guess costs is the QUESTION --
    a written repo id reads as known, so nobody queries the manifest. State the mechanical
    half first, because "do not guess" is advice about judgement and the next person under
    time pressure will violate it.
11. **A closer must check for items waiting on an EVENT, not only for items naming its
    number, and no grep finds those.** `.todo/682` was gated on "the first published
    checkpoint that runs end to end"; it fired THREE times unnoticed, because the trigger
    lived in 682 while the people firing it were closing `677` and `678`. What works: when a
    Done section describes a capability arriving for the first time, **grep `.todo/` for the
    CAPABILITY** -- the format, the model class, the surface -- not the number.
12. **A directory-local ignore rule protects by LOCATION, so moving the rule stops
    protecting whatever stayed** -- and what stayed is invisible to the rename precisely
    because being ignored is what kept it out of it. 682 moved `examples/llama2/.gitignore`
    correctly and the next `git add` swept 61 MB onto develop. **The tree has 18
    directory-local `.gitignore` files**, several covering whole build trees, so this is a
    standing property. The check is one command at the one moment the files are visible:
    **after moving a directory that contains a `.gitignore`, run `git status --porcelain`
    for untracked files at the OLD path before the next `git add`.** And the fix is free
    only on the box that makes it -- untracking DELETES the file for every puller who had
    it. Full account, with the other three things that rename broke outside its own diff:
    `.todo/708` (closed 09-06 --
    `git show 97c85518~:.todo/708-the-formatter-corpus-walks-agent-worktrees.md`). **The
    card is `.kb/directory-rename.md`** (`711`, closed 09-06), and it carries the two
    mechanics this rule did not: the exposure needs a PER-FILE rename, because `git mv` on
    the DIRECTORY takes untracked files with it, and the check needs `-uall` or the residue
    is one collapsed `?? old/` line.

## What is deliberately not in the plan

- **Not an inference framework.** The forward pass stays one Lisp file; the layer became a
  KIND with options (`676`) and two more kinds joined it (`677`, `678`) only because the
  newest small models are hybrids. Gemma 4 waits until asked for.
- **Not mixed-precision training.** `torch:` stays f32/f64; bf16 is a storage width for
  weights, and nothing here changes what an activation is.
- **Not the device.** `--gpu` declines every new type until `.todo/490`; declining correctly
  is what `.todo/483`'s exhaustive switches buy.
- **Not fp8 / int4 on the CPU.** Measured out; re-measure only when the Vector API grows a
  dot-product or a narrower conversion, or on a host whose JIT beats 1 op/element for the
  unpack.
