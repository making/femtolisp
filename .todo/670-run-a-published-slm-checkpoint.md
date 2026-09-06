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
| **Q8_0** (32 int8 + a scale) | a **read-only weight matrix** type with an integer-dot GEMV: 1.4-1.6x f32 on one thread under Graal and 1.7-1.9x under C2, 2.2-3.3x on 20, a quarter of f32's bytes | `.todo/672`, `.todo/706`, both closed |
| **Q4_0 / Q4_K** | not a CPU item: the nibble unpack is ALU-bound at 5.7 GB/s (1.1x f32 for 8.5% error). **Refused on the device too** (2026-09-06, `718`, on a decode profile, not a pointer) -- and its re-open trigger (a) FIRED the same week, `723` and `725` having taken the forward 45 -> 18.5 ms while the GEMV held at 6.7 | `.kb/gpu.md`, "What is deliberately NOT here" -- the refusal, the fired trigger and the arithmetic; the re-measurement is `.todo/726` |

Two facts under all four: **the width is bandwidth, not fitting** -- 4.4 GB of f32 fits an
8 GB laptop -- and **every kernel number is JIT-dependent**: the spike's fused kernel fell
to 0.20x under C2 from an inlining cliff, so `.todo/488` takes its numbers under both JITs.

## Children: all closed

`671` (bulk widening), `673` (GGUF reader), `674` (byte-level BPE), `675` (safetensors +
`config.json`), `676` (the forward pass as a table of layer kinds), `677` (Gated DeltaNet),
`678` (LFM2 gated short-conv), `672` + `706` (the Q8_0 matrix and its integer dot), `489`
(the model rungs at f32 and bf16), `490` (bf16 on the device). Each owns its own numbers
(rule 9); the measured rungs live in `examples/llm/README.md`, the closing accounts in
`.todo/history/2026-09.md`.

The order they were taken in -- `671` before either reader -- is the one reusable part:
`671` needs no new array type, so a BF16 checkpoint loaded into `#f` BEFORE the bf16 width
existed and the readers were debugged at f32 with the kernels out of the picture.

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
estimate anywhere. A cross-model GB/s comparison is NOT evidence here: it divides by an
activation-blind parameter-count estimate that omits exactly Qwen3.5's recurrent state.

Two routes reach it, both `489`'s:

1. **The knee did not move when the bytes halved** (six models, two widths) -- flat by 16
   threads in both arms for Qwen3.5, TinyLlama and both SmolLM2s, still climbing at 32 in
   both for LFM2.5. A DRAM cap would have pushed the knee outward. **Byte-estimate-free,
   and the strongest leg.**
2. Dorian's knee at f32 alone: 1 -> 32 threads is **4.37x** for LFM2.5 against **2.95x**
   for Qwen3.5.

A third route -- GB10 reading the same 41-42 Gelem/s at 4.2 MB and at 67 MB -- was
**withdrawn by `.todo/702`**: the rate over shape is a HUMP and those were two points on
it. It is named here only so a reader who met it elsewhere knows it was retired.

`702`'s own sweep (parallel f32 GEMV, 256x256 to 4096x4096, no model in it, quiet GB10)
says the cap is real but is neither one number nor one mechanism: below ~500 rows the work
is not cut finely enough to fill the box (256x256 runs at 0.89x its own SERIAL rate), above
~30 MB the bytes bind, and in between the kernel reaches 48-69 Gelem/s -- 1.5x what was
being quoted as the box's limit. The 3072x3072 dip in the middle is unexplained
(`.todo/713`). Record and the leaf/grain arithmetic:
`.todo/artefacts/702-the-parallel-cap-is-the-machinery-or-memory-one-run-decides/README.md`.

Carry one consequence: **a parallel GEMV rate is a property of how the work was cut up**,
not of the machine and not of the weights. The 10x collapse once seen while two lanes
shared a box is `.todo/697`'s mechanism, not a property of anything measured here.

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
`smollm2-135m-f16` GGUFs, and `tools/` (`llama.cpp`). On **GB10**,
`/home/maki/models/qwen35-gguf/` and `qwen35-hf/`, hash-verified 09-05; the HF cache holds
only `unsloth/Qwen3.8-Flash-Next-GGUF`, so any other GB10 lane re-fetches. None of it
belongs in the repo, and `examples/llm/.gitignore` is what keeps the two `stories15M`
artefacts out -- see rule 12.

## The certification record

**dorian certifies `a92e205f`** at the close of A's six-item lane: 10104 / 0 / 0 / 283
skipped with **235** reports. **GB10 certifies `e2d8d1591`** at the close of B's
five-item lane: 10122 / 0 / 0 / 189 skipped with **237** reports, exit 0, `GpuTest`
included. Both were taken by the
ORCHESTRATOR on `develop`, not from any lane's worktree (rule 4) -- a lane's combination
exists nowhere else. The two heads are days of work apart and are NOT one certification;
jointly they establish that no box is red. `d4225aa5` remains the last head both boxes
actually held, and `a92e205f` is already behind `develop` on B's `am.ik.gpu` /
`eval/LinalgGpu*` / `codegen/jvm/JvmGpuTemplate` drift, which dorian cannot verify at all.

**What a run certifies is failures, errors and the REPORT-FILE COUNT -- never the totals.**
A differing report count means a class was DROPPED rather than skipped, which no skip
accounting reveals. The count walks 232 -> 234 (`707`, and separately the two classes the
prior A lane added) -> 235 (`710`); A's six-item lane held it at 235, so nothing it wrote
created or dropped a class -- every test went into an existing class or into
`ci-spec.yaml`. B's five-item lane reads **237**, and it added THREE classes
(`am.ik.gpu.DeviceResidencyPressureTest` from `716`, `eval/LinalgBfloat16Test` and
`eval/LinalgWidthWireTest` from `687`), so the arithmetic does not close by one. Neither
prior report SET still exists to diff against, which is the whole point of the discipline:
the next run on either box diffs the LIST and names what left, because a count that misses
by one is exactly what a dropped class looks like.

Three things a reader needs before comparing two runs:

1. **A total taken before `0e65326b` is not comparable to one taken after.**
   `LispFormatterTest` used to walk `Path.of(".")` and format every `.lisp` under
   `.claude/worktrees/`, so one term of the comparison was how many agents had run on that
   box recently. The walk now excludes `/.claude/` and
   `repositoryCorpusStaysWithinASmallFactorOfTrackedSources` pins it. After that point
   totals ARE comparable across boxes.
2. **Skips are comparable, and the cross-box difference has been reproduced**: 276 on
   dorian against 189 on GB10 is exactly the **87** that `.todo/708` derived from seventeen
   differing classes on ONE box. Contamination never reached skips.
3. **A skip count is only a signal against a prior count for the SAME slice.** Its designed
   meaning and its defect meaning are the same integer, and `Tests run` is invariant under
   skipping but not under deletion -- a skipped leg keeps the headline total while removing
   the coverage, which is how `682` was accepted by a run that skipped the part of the
   suite its rename was most likely to break. dorian's 276 -> 283 delta across seven items
   and two lanes is therefore left UNATTRIBUTED; `CiSpecE2eTest` contributes 0 to it, since
   the `--simd` axis doubles the legs of a class that reports zero tests when
   `-Drontolisp.binary` is unset and shows up only in the native run (2008 -> 4020 cases).

The argument for taking the run from `develop` rather than a worktree is that it is the run
that found the red: `LispFormatterTest`'s walk raced a scratch file another test writes into
the project root, giving `Tests run: 9425, Errors: 1` while six lanes' worktrees were green
throughout. Fixed by `visitFileFailed -> CONTINUE`, so an entry the walk cannot read is not
a corpus member. **Note what the total did there** -- 9425 against 10093 is the signature,
and point 3 above is the case where it is not.

Twice, coverage fell into the SEAM between two correct plans -- nobody skipped an assigned
step, and the combination was what nothing covered. **A verification owed by one party and
skipped by everyone else is a gap that looks exactly like coverage until someone checks who
actually ran it.** `.todo/709` Part 2 keeps that kind separate from record failures.

## Findings from the run, and where each one now lives

Pointers, not records -- the home is where it gets updated.

- **`483`'s rule is stated wrong in 483**: not "never write a `default`" but **"an arm
  matching two or more permits IS a default, whatever it is spelled"**. In `.kb/vec.md`.
- **`%la-gather-strided` has five readers** and grepping the name finds two. `.todo/687`.
- **Seven sites hand-write the bf16 conversion arithmetic** and only
  `am.ik.rontolisp.BFloat16` is the authority. Census: `.todo/487`'s remainder.
- **`.kb/string-index-cost.md`** records what `690`'s 340x is and is not.
- **A profile names the COST correctly and the CAUSE only as a guess.** `718` measured the
  three DeltaNet functions to the millisecond and was right about all three; its two guesses
  at WHY two of them were boxed -- unary minus outside the typed subset, oddly shaped loops
  -- were both wrong, and the real causes (a `(length v)` COUNT, and a conv kernel narrowed
  to a weight width it is F32 in the file) were found in a minute with a flag that prints
  the declining form (`-Drontolisp.debug.typedlooptrace=true`, added by `723`). Where a
  profile hands over a "probably because", the closer measures that half too.

## Lanes: ONE worker per orchestrator

**Each orchestrator drives ONE lane at a time, serialized: an item completes, is committed
and pushed, and only then does the next start.** What that buys is the thing two lanes cost
-- the surface-accounting overhead in `.todo/709` exists entirely because two lanes on one
box can touch one mechanism without either seeing the other.

Model by difficulty, `effort=high` throughout: **High -> Fable, Medium -> Opus, Low ->
Sonnet.** A dead worker is RESUMED, never respawned. An item the lane's work turns up is
FILED and left for the next lane's planning, never worked recursively.

**The pool is split by the one thing the boxes do not share.** Every item that needs the
DEVICE is B's, because only GB10 can supply one; **every GPU-free item is A's**. That is
the whole partition, and it now covers the pool exactly.

### Orchestrator A -- dorian, GPU-free, the model side

A's previous lane closed `697`, `700`, `698`, `686`, `694`, `704` and certified `a92e205f`.
**Three of the six had their premise moved by their own measurement**, which is the result
that outlived them:

- `697` -- the contention trap is a TAIL, not a floor. "One other build costs 10x" did not
  reproduce; what reproduces is two 64-thread decode loops at once. The default moved to
  `min(cpus, max(2, cpus/2))` for a different reason -- the IDLE-box curve bends down past
  half the box (16 threads 9.55 tok/s, 32 9.45, 64 8.70). The rescue design the item
  proposed was built and measured 0.041 -> 0.061 ms/call at every budget, so its cost is
  the flag's cache line rather than the rescue; dropped.
- `694` -- the second corpus pass was predicted nearly free (fixed-cost) and is
  marginal-dominated: 746 ms fixed against 479 x 74.8 ms, a whole pass. Taken anyway, with
  the reason written down, because the axis is what `692` proved a backend count cannot see.
- `704` -- both quadratic sites are linear now (JVM `read-file-string` 18,164 -> 537 ms on
  2.7 M chars, n-ary `concatenate` 54,197 -> 357) and `examples/llm`'s `read-file-bytes`
  detour still STAYS: the byte path remains 2x (JVM) to 23x (interpreter, wasm) faster,
  because what is left is character `read-sequence` itself. Residue: `.todo/721`.

`700` also killed its own second option empirically -- a jar with an `Add-Modules` manifest
attribute still throws `ClassNotFoundException` under `java -jar` -- and `698` found a wider
defect than the one filed: a SIMPLE packed float vector's `subseq` threw outright on the
interpreter, so the reported adjustable case was the narrow half. Left behind:
`--simd --parallel` a hard error instead of a silent 100x degrade on the interpreter path,
`vec:`'s sixteen mixed-width `throw` sites declining to the scalar defun,
`CiSpecE2eTest`'s `Accel` axis with a "did `--simd` take effect" assertion verified by
breaking it three ways, `.kb/simd-parallel.md`, `.kb/string-accumulate-cost.md`, and
`.kb/vec.md`'s "The E2E `--simd` axis".

**The current lane closes what the last one halved, then returns to the checkpoint path.**
Four of the six are one mechanism seen from four sides -- which specialized array type
survives an operation -- and the interpreter half of each is already correct, so every one
of them is an ASYMMETRY rather than a fresh defect. That is rule 6's shape, and `694`'s new
`--simd` axis is the instrument that now catches it.

| # | item | difficulty | why here, why now |
| --- | --- | --- | --- |
| A-1 | `720` JVM and wasm `--simd` still signal on a mixed-width `vec:` pair | Medium | FIRST because it is `686`'s other half and the invariant is currently true on one engine only: **a speed flag must not decide whether a program runs.** The interpreter declines to the scalar defun; the compiled backends still throw, where there is no closure to hand back. It is also the first thing the new `--simd` axis is pointed at, so a red here is the axis working |
| A-2 | `719` JVM and wasm `subseq` of a packed vector loses the width | High | The lane's only High. Same compiled-array machinery as A-1 (`JvmArrayCompiler`, `WasmArrayCompiler`, `JvmIntArrayRuntimeBuilder`), taken while that context is warm. `698` already landed the interpreter side and left a `ci-spec` case whose `expectedByBackend` RECORDS the wrong answer -- closing this deletes that exception, which is the completion test |
| A-3 | `703` an unknown `make-array` element type answers a boxed array | Medium | The same closed element-type space from the CONSTRUCTION side. `707` made the result-type normalizer carry the `ArrayElementTypes` code instead of a transcribed width; `make-array` still decides by hand, so an unrecognized type degrades silently. The item cites rule 6 itself: the defect sits entirely on one side of its condition |
| A-4 | `714` `coerce` / `concatenate` to a `(vector character)` result | Low | The last member of that space `707` left behind -- one specialized code still answering a general vector. Smallest of the four and done while the space is in hand, not because it is urgent |
| A-5 | `693` the wasm-component leg of `safetensors-check` traps on the sharded read | Medium | The first checkpoint-path defect rather than a representation one, and it contradicts the umbrella's own claim: four backends, and this one dies on a sharded index read. Already isolated -- present with and without `--simd` and on a jar built before `692`'s fix -- so the diagnosis starts clean rather than in a bisect |
| A-6 | `705` no suite asserts on decoded text for any model | Medium | Last deliberately: it pins what the five above can move, so it wants them landed. Its own episode is the argument -- a lane spent a build-and-bisect cycle deciding whether the decoder had changed, and the answer was that two views of ONE string had been read as two strings. A pin makes that question answerable without the cycle |

**A's pool, not in the lane.** All GPU-free, none blocking the checkpoint path:

- `684` (the f64 GEMV row is one accumulator chain) and `696` (narrow-width element-wise
  kernels and the operand pairing) each hold an **x64 half that is A's**, since every
  `.todo/488` number behind them is aarch64. Neither is on the width chain's critical path.
- `721` (`704`'s residue: character `read-sequence` costs ~1.2 us/char) is a kernel-cost
  item wanting a quiet box, and it does not decay.
- `722` (High -- the WASM component backend traps on a `ref.cast` that a one-line source
  edit MOVES; passing and trapping adapters are byte-identical. **Its prediction came
  true the same day, twice**: eleven lines of `723`'s, ~500 characters, touching nothing
  the component path is suspected of, trapped the whole corpus -- before AND after
  `693`'s adapter fix landed with 30 corpus lines of its OWN that passed. So it is a coin
  flip per case; `723` dropped its case rather than ship red, and the corpus is not one
  case FROM the cliff, it is AT it. **Every future cross-backend pin is behind it**)
  and `724` (every `tok/s` row in
  `examples/llm/README.md` was taken with a harness that divides sampled tokens by the
  prompt's clock and counts the JIT warm-up, so the rows read 1.7-2x low). Both filed by
  B's lane off `718`'s profile.
- `597` (the other four `geom:` model readers have no interpreter native), `689`
  (`jvm-export` handles do not carry bfloat16), `699` (one UTF-8 lead-byte table, two
  hand-written copies), `701` (diff every checkpoint's own `chat_template` against the
  hand-written one -- a measurement, not a renderer), `715` (ANSI conformance, the ranked
  gap), `695` (the `.kb` compaction follow-up).

### Orchestrator B -- GB10, the device

B's previous lane closed `717`, `716`, `687`, `718`, `713` and certified `e2d8d1591`. What
the "done" markers understate is that **the lane's one High closed as a written REFUSAL and
paid for itself in the profile that justified it**: `718` measured where a 45 ms forward
actually goes -- device kernels 7.5 ms, driver calls on the calling thread 11.9, and the
rest the HOST -- so Q4 on the device was declined on arithmetic (a Q4 GEMV saves at most
4-5 ms of 45, and the CPU arm already runs at 24), and the items below are what that profile
found instead. Also left behind: a re-upload counter that speaks only when the residency
budget is under the working set (`716`), `linalg:`'s width protocol widened from a boolean
to a code so bfloat16 rides it (`687`), a differential test that finally asks about
`vec:matvec` and builds a real bf16 operand (`717`), and the 3072x3072 dip localised to a
byte-size trough that needs more than 12 threads to appear at all (`713`).

The current lane is again **every pool item that needs the DEVICE**, and after `718` they
have one subject between them: **the device arm's HOST floor.** The GEMV is not the cost
any more, so each item here is a place where the flag makes the host slower, or moves bytes
that never needed to move. Order matters more than usual, because each one changes the
denominator the next is measured against.

| # | item | difficulty | why here, why now |
| --- | --- | --- | --- |
| B-1 | `723` `--gpu` puts a residency guard in front of every typed store, and two loops were never typed | Medium | **CLOSED 2026-09-06: the device forward halved, 51 -> 25 ms, and the arm now edges past `--simd --parallel` (26.7).** FIRST because it is the DENOMINATOR. The DeltaNet mixer costs 6.8 ms a forward on the `--simd` build and ~30 ms on `--gpu --simd` -- about 40% of the arm spent proving arrays are not on the device -- so until it is hoisted, every later measurement on this box is taken through a host arm inflated 4x and a before/after for B-2 or B-3 is a ratio of two wrong numbers. It is also the first of the two conditions `718` wrote down for reopening Q4 |
| B-2 | `725` attention multiplies the full seq-len KV cache every token | Medium | **CLOSED 2026-09-06: 24.9 -> 18.5 ms a forward on the device arm, 88.4 -> 76.7 on one `--simd` thread, 26.3 -> 21.6 on sixteen; the 100 MB a forward stopped going up.** Taken against B-1's corrected host: its ~6 ms was a QUARTER of the device forward, not an eighth. The fix is in `examples/llm/llm.lisp`, NOT in `vec:` -- the cache grows with the position reached (`grow-kv-cache`), which bounds BOTH products, where the row-count argument this item proposed bounds only the keys: the value cache is transposed and wants a COLUMN bound. **A bound one operand of the pair cannot spell is a bound in the wrong place**, and the library surface is unchanged. It also fired the Q4 refusal's trigger (a) -> `.todo/726` |
| B-3 | `476` `am.ik.gpu`'s downcalls go through the generic `MethodHandle` invoker | Medium | Last deliberately: it is ~8% of a step, it does not decay, and 8% of an inflated step is a different number from 8% of a corrected one, so its before/after is only worth taking once B-1 and B-2 have landed. The item files itself Low-Medium and the lane rounds UP -- the edit is mechanical (instance handles to `static final`, in `CudaDriver` and in `--blas`'s `LinalgBlasKernels` the same way) and the CLOSE is a profile, which is the half this box keeps finding costs the judgement |

**Not in this lane, and why.** `514` (`LinalgGpuTest` never finishes on an Apple silicon
Mac) is the one device item NEITHER orchestrator can take: it wants a Metal box, and GB10 is
CUDA on aarch64 Linux. Parking it is not a deferral under rule 3 -- there is no box in this
plan that can even fail it. `684` and `696` stay A's for their x64 halves.

**Filed by this lane and handed to A's pool, never worked recursively:** `722` and `724`,
both GPU-free, which is the partition doing its job. `726` (B-2's own residue -- Q4 on the
device, whose re-open trigger B-1 and B-2 fired between them) is B's, and is the one item in
this plan whose ARRIVAL was written down in advance by the item that refused it.

**The one decision still open, and not either lane's to take alone: `.todo/709` is an
explicit DRAFT and needs co-signing or cutting by both orchestrators.** It is process, so
one side adopting it unilaterally is the failure it is written about. It has now outlived
two full lanes, which is evidence about the item rather than about its subject. It is also
where the general reading disciplines belong -- diff the lists rather than reasoning about
which terms ought to differ; a sum that closes is not evidence about its terms; relay a
census with its total AND its class count -- **there or nowhere**.

## Standing rules this run earned, in the order they cost the most

Cited by number from other items -- **the numbering is fixed.**

1. **Only the closer can write back a dependency.** Six items closed in one day and twelve
   open todos still read as blocked by them that afternoon. The grep for items naming the
   number belongs beside the history row in the close procedure.
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
    it. **The card is `.kb/directory-rename.md`**, and it carries the two mechanics this
    rule does not: the exposure needs a PER-FILE rename, because `git mv` on the DIRECTORY
    takes untracked files with it, and the check needs `-uall` or the residue is one
    collapsed `?? old/` line. The full account of the other three things that rename broke
    outside its own diff is `git show 97c85518~:.todo/708-the-formatter-corpus-walks-agent-worktrees.md`.

## What is deliberately not in the plan

- **Not an inference framework.** The forward pass stays one Lisp file; the layer became a
  KIND with options (`676`) and two more kinds joined it (`677`, `678`) only because the
  newest small models are hybrids. Gemma 4 waits until asked for.
- **Not mixed-precision training.** `torch:` stays f32/f64; bf16 is a storage width for
  weights, and nothing here changes what an activation is.
- **Not the device, beyond the GEMV.** `--gpu` takes `vec:matvec` over bf16 weights since
  `.todo/490` and declines every other new type; declining correctly is what `.todo/483`'s
  exhaustive switches buy.
- **Not fp8 / int4 anywhere.** On the CPU, measured out; re-measure only when the Vector
  API grows a dot-product or a narrower conversion, or on a host whose JIT beats 1
  op/element for the unpack. On the device, refused by `718` on the decode profile (the
  width table's row); re-measure on that row's two triggers, not before.
