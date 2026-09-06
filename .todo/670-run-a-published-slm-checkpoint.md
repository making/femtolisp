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
| **Q8_0** (32 int8 + a scale) | a **read-only weight matrix** type with an integer-dot GEMV: 1.4-1.6x f32 on one thread under Graal and 1.7-1.9x under C2 (the 09-05 kernel was 0.7x under C2: `.todo/706`), 2.2-3.3x on 20, a quarter of f32's bytes | `.todo/672` and `.todo/706`, both closed |
| **Q4_0 / Q4_K** | not a CPU item: the nibble unpack is ALU-bound at 5.7 GB/s (1.1x f32 for 8.5% error). A device width -- **and no item has ever built it**; `490` was bf16 and closed 09-06 without touching a nibble | `.todo/718` |

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
| `672` | the Q8_0 weight matrix and its integer-dot `vec:matvec` | High | **closed** 09-05; its one-thread follow-up `.todo/706` **closed** 09-06 |
| `675` | read a safetensors file (+ `config.json`) | Medium | **closed** 09-06; the reader's `#bf16` destination is now pinned across the two engines that have the width (`cli/SafetensorsBfloat16CompilePathTest`) |
| `677` | the Gated DeltaNet layer: Qwen3.5-0.8B, and every Qwen 3.5-3.8 dense model | High | **closed** 09-06: the layer and its pin (`examples/llm/deltanet.lisp`, `deltanet-check.lisp` on all four backends with and without `--simd`) landed 09-03; its "Remaining" -- the bf16 and quiet-box `tok/s` rows -- was `489`'s lane's work and is in `examples/llm/README.md`. Re-verified at the close on `40a80f91`: one 64-token answer from safetensors and GGUF at f32 and bf16, 1 and 32 threads (eight runs, token for token) |
| `489` | the model rungs: TinyLlama / SmolLM2, Qwen3-0.6B, LFM2.5-1.2B, Qwen3.5-0.8B | **closed 09-06** | f32 and bf16 measured on six models 09-05; result and reading now in `examples/llm/README.md` and `.todo/history/2026-09.md`. The fused pairing is bf16 weights against f32 activations only, every other pairing declining to the scalar defun (`.todo/696`) |
| `490` | bf16 on the device | High | **closed 09-06** on the GB10: `gemv_bf16` (the f32 kernel over the widened matrix bit for bit), both interceptors, Metal declining by `supportsBfloat16()`; the accumulator became a compensated float pair at `#f` and `#bf16` because the double FMA was a compute ceiling on this card (`.kb/gpu.md`, "The GEMV, and the matrix that stays"); the residency cap was never the constraint under the interceptors (lazy budget = the device less an eighth). Numbers: `examples/llm/README.md`, "bf16 weights on the device" |

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
`cli/SafetensorsBfloat16CompilePathTest` (A-3).

**GB10 certifies `97e3b9ba`**, at the close of B's whole lane: 10094 / 0 / 0 / **189**
skipped with **235** reports, exit 0, `GpuTest` included. Taken by the ORCHESTRATOR on
`develop`, not from any lane's worktree (rule 4) -- the six items' combination exists
nowhere else. The report count walks 232 -> 234 (707) -> 235 (710); B-5 and B-6 added
tests to existing classes and moved it no further. The mid-lane certification was
`bc83d23c` (10080 / 0 / 0, 235 reports) after B-1 through B-4.

**And the run that certified the lane is the one that found the red**, which is the whole
argument for taking it from `develop`. At `91a5ec0c` the suite came back
`Tests run: 9425, Errors: 1` -- `LispFormatterTest`'s corpus walk hit
`NoSuchFileException: ./ci-stream-value.txt`, a scratch file another test in the same run
writes into the project root and deletes again. Six lanes' worktrees were green
throughout; the walk has always been able to lose that race, and `708` giving it a second
caller only widened the window. Fixed in `97e3b9ba`: `walkFileTree` with
`visitFileFailed -> CONTINUE`, so an entry the walk cannot read is not a corpus member.
**Note what a `Tests run` total did here** -- 9425 against 10093 is the signature, and
rule 3's "a skipped leg keeps the headline total" is the case where it is not.

The two heads are three days' work apart on one branch and are NOT one certification; what
they jointly establish is that no box is red. `d4225aa5` remains the last head both boxes
actually held.

**Totals are now comparable across boxes** -- 708 landed 2026-09-06 and the corpus stopped
counting other agents' worktrees -- **and the first cross-box comparison since confirms
the skip census**: 276 on dorian against 189 on GB10 is a difference of exactly **87**, the
number `.todo/708`'s accounting derived from seventeen differing classes. It was derived
from two censuses on ONE box and has now been reproduced as a live difference between two.
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

## Lanes: ONE worker per orchestrator

The two-worker arrangement is over. **From here each
orchestrator drives ONE lane at a time, serialized: an item completes, is committed and
pushed, and only then does the next start.** What that buys is the thing two lanes cost --
the surface-accounting overhead in `.todo/709` exists entirely because two lanes on one box
can touch one mechanism without either seeing the other.

Model by difficulty, `effort=high` throughout: **High -> Fable, Medium -> Opus, Low ->
Sonnet.** A dead worker is RESUMED, never respawned.

**Orchestrator A -- dorian, the model side, no GPU.**

The previous A lane closed all five of its items -- `489`, `712`, `675`, `677`, `711` -- one
at a time, and certified the suite at `ff903aa8` above. Two of the five had nothing left to
BUILD: `675`'s remainder was landed by `487` and `677`'s by `489` and `678`, and rule 3's
sort into blocked / not-done / done-elsewhere is what established that rather than a count.
What it left behind: `cli/LlmChatModeWithoutTemplateTest`, `cli/SafetensorsBfloat16CompilePathTest`
(the first pin of the bf16 reader across two ENGINES, where every part had been pinned on
one engine each), and `.kb/directory-rename.md`.

The current lane is drawn from the unassigned pool and is **GPU-free by construction** --
dorian has no device, so every item in it must be verifiable without one. There is no High
item: the GPU-free pool holds only Low and Medium, so nothing here is Fable's.

| # | item | difficulty | why here, why now |
| --- | --- | --- | --- |
| A-1 | `697` `--parallel`'s default thread count is a trap on a shared box | Medium | FIRST, because it is the one item that invalidates the others' evidence rather than adding to it. Its measurement is dorian's own: the default 64 threads against 32 is **16x slower** with one other build on the box, and 4x slower than ONE thread, because a worker preempted while holding a leaf costs the whole GEMV a scheduler quantum. A takes every future rung on this box and every `--parallel` row in `examples/llm/README.md` was taken at the default, so until this is fixed the lane cannot separate a codegen change from a descheduled worker |
| A-2 | `700` `--simd` without the incubator module is a 100x cliff that reads as a hang | Low | The same failure shape as A-1 -- a run that is neither broken nor working -- on the same flag surface (`RontoLispCli`, beside `requireSimdForParallel`), so one worker holds both while the context is warm. The mechanics are correct and documented; what is wrong is the SEVERITY of a one-line warning in front of a 100x degrade. Check the item's second option before proposing it: `Add-Modules` is not a manifest attribute the launcher honours for `-jar` |
| A-3 | `698` `subseq` of an adjustable packed vector is a `simple-vector` | Low | A live correctness defect that kills the INTERPRETER leg on a real checkpoint's `tokenizer.json`, which is why the Qwen3.5 rows in the README are JVM-only and nobody saw it. Rule 6's shape exactly: two defects, and the second -- three backends tolerating a general-vector `subseq` -- is what hides the first everywhere but the one place it bites |
| A-4 | `686` `--simd` turns a mixed-width `vec:` call into an error the scalar path answers | Low | Unblocked: `484` landed the `defineFn` hand-back this needs, so the work is turning 16 `throw mixedWidth(...)` into `return null`. Here because it is A-3's invariant from the other side -- **a speed flag must not decide whether a program runs**, which is what every cross-backend bit-identity pin in `.kb/vec.md` exists to protect |
| A-5 | `694` the cross-backend E2E corpus has no `--simd` axis | Medium | After the three above, deliberately: it is the AXIS each of them sits on one side of, so the cases this lane has just written are what the new axis first crosses. It is also the item rule 6 was written from -- `671` closed green on four backends with a native run, and every test it wrote sat on the non-`--simd` side of the condition that broke it on two of them |
| A-6 | `704` accumulating a file into a string is quadratic in the file | Medium | On A's own path -- 12.8 MB of `tokenizer.json` never returns, sampled at 200 s still inside the accumulate -- and independent of everything above it, which is why it is last: its value does not decay. Both call sites and the already-linear path (`read-file-bytes` + `rontolisp:octets-to-string`) are written down in the item |

**Not in this lane, and why.** `687` (`linalg:` carries its element width as a boolean) is
GPU-free to WRITE and not to VERIFY: it changes `LinalgGpu.gatherStrided`, and only GB10
can run that arm. `684` and `696` each hold an **x64 half that is A's** whenever they are
picked up, since every `.todo/488` number behind them is aarch64 -- but neither is on the
width chain's critical path, so they stay in the pool.

**Orchestrator B -- GB10, the device.**

The previous B lane closed all six of its items -- `708`, `702`, `707`, `710`, `490`, `706`
-- one at a time, and certified the suite at `97e3b9ba` above. Two results the "done"
markers understate: `702` cost route 3 of the parallel argument outright, and `490` closed
the width chain with a NEGATIVE result -- the device arm loses to `--simd --parallel` on
this box. What it left behind: `PathCitationTest`, `.todo/artefacts/` as a root, a Q8_0
GEMV that C2 runs at 1.9x of f32 where it used to run at 0.72x, and five filed items it
deliberately did not work recursively.

The current lane is **every unassigned item that needs the DEVICE**, which is the one thing
only this box can supply. `713` joins them for the box rather than the device: it is
`702`'s remainder and wants the same cleared machine.

| # | item | difficulty | why here, why now |
| --- | --- | --- | --- |
| B-1 | `717` `GpuOfferDifferentialTest`'s stale bf16 operand and the offer it never asks | Low | FIRST because it is the lane's INSTRUMENT. That test is what says which types the interpreter offers the device and which the device refuses, and it builds its bf16 operand from a stub that predates the width while never asking about the `matvec` offer at all -- the exact offer `490` added. Every later item here is verified through it. Rule 6's shape, precisely: a differential test with a hole looks more exhaustive than one without |
| B-2 | `716` a model over the residency budget decodes BELOW `--simd`, silently | Medium | The only item in the lane that is a wrong OUTCOME a user meets rather than a representation or a measurement: over budget the model decodes at 6.8 tok/s against `--simd` alone at 7.7, and nothing prints. It is also half of "what does the device arm wait on", answerable by making an existing cliff visible, which is why it comes BEFORE `718` rather than out of it |
| B-3 | `687` `linalg:` carries its element width as a boolean | Medium | A's lane ruled it out for the right reason -- GPU-free to WRITE, not to VERIFY, because it changes `LinalgGpu.gatherStrided`. Here now because `707` just did the analogous change on `coerce` / `concatenate`: the element type carried as a code derived from the permits, instead of a width transcribed into a second list. The pattern is proven and fresh, and `718` would otherwise have to route its kernel work around the boolean |
| B-4 | `718` Q4_0 / Q4_K on the device | High | Last of the device items deliberately. Its first Done is a PROFILE and not a kernel -- what does this box wait on once the GEMV is off the critical path -- and that profile is worth more after `716` has made the residency cliff visible. **A written refusal is an accepted outcome**; the item exists because the width table cited a closed item and so read as done |
| B-5 | `713` the 3072x3072 parallel GEMV dip | Low | Not a device item -- a `--parallel` f32 sweep at finer granularity -- but it needs the same cleared box, so it belongs to whoever holds that box. Last because it gates nothing and does not decay: `702`'s record holds its evidence durably, and an unexplained dip stays unexplained at the same cost later |

**Not in this lane, and why.** `684` and `696` are the two pool items whose prior numbers
are GB10's, so B could measure them -- but each holds an **x64 half that is A's** the
moment it is picked up, and neither is on the device. Splitting one across two boxes is
what `.todo/709` Part 2 is about, so they stay whole in the pool until one side takes both
halves. Everything else in the pool is GPU-free and therefore not B's to hold.

**The one decision still open at planning, and it is not either lane's to take alone:**

**`.todo/709` is an explicit DRAFT and needs co-signing or cutting by both orchestrators.**
It is process, so one side adopting it unilaterally is the failure it is written about. It
has now outlived two full lanes, which is evidence about the item and not about its
subject: a process draft with no owner is exactly the thing both lanes keep rediscovering.
It is also where the general reading disciplines belong -- diff the lists rather than
reasoning about which terms ought to differ, a sum that closes is not evidence about its
terms, relay a census with its total AND its class count -- **there or nowhere**; `711`
closed without them by design.

The two other standing decisions are discharged: the one-thread bf16 ratio went into
`489` before it closed, and `711` is closed.

**Unassigned pool, neither side's yet:** `597`, `684`, `689`, `693`, `695`, `696`, `699`,
`701`, `703`, `705`, `714`, `715`. `683` left it by closing. `687` left it to B, which is
the only side that can verify it. `713`, `716`, `717` and `718` never really entered it --
B filed the last three and inherited the first, and all four need this box.

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
- **Not the device, beyond the GEMV.** `--gpu` takes `vec:matvec` over bf16 weights since
  `.todo/490` (09-06) and declines every other new type; declining correctly
  is what `.todo/483`'s exhaustive switches buy.
- **Not fp8 / int4 on the CPU.** Measured out; re-measure only when the Vector API grows a
  dot-product or a narrower conversion, or on a host whose JIT beats 1 op/element for the
  unpack.
