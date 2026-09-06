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
| **Q4_0 / Q4_K** | not a CPU item: the nibble unpack is ALU-bound at 5.7 GB/s (1.1x f32 for 8.5% error). **Refused on the device too** (2026-09-06, `718`, on a decode profile, not a pointer) -- and its re-open trigger (a) has since FIRED, `723` and `725` having taken the forward 45 -> 18.5 ms while the GEMV held at 6.7 | `.kb/gpu.md`, "What is deliberately NOT here" -- the refusal, the fired trigger and the arithmetic; the re-measurement is `.todo/726` |

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

**The device arm now leads.** `--gpu --simd` is 18.5 ms a forward on Qwen3.5-0.8B against
`--simd --parallel`'s 21.6, where it trailed 1.9x one lane ago; neither change that moved it
touched a kernel (`723`, `725`). The rows are `examples/llm/README.md`'s.

**The parallel leg is bound by the parallel machinery, not by DRAM** -- distribution,
barriers and per-row dispatch -- and **how much a model pays depends on how its work is cut
up**: Qwen3.5's Gated DeltaNet does 576 small 128x128 GEMVs per token against LFM2.5's ~30
big matvecs, so the one paying more dispatch peaks earlier. The signature is a
model-specific SATURATION POINT carried by within-model scaling only, tok/s over tok/s. **A
cross-model GB/s comparison is NOT evidence here**: it divides by an activation-blind
parameter-count estimate that omits exactly Qwen3.5's recurrent state.

The evidence is `489`'s -- **the knee did not move when the bytes halved**, six models and
two widths, where a DRAM cap would have pushed it outward; the byte-estimate-free leg and
the strongest one. A third route, one rate read at two sizes, was WITHDRAWN by `.todo/702`:
the rate over shape is a HUMP and those were two points on it. `702`'s own sweep says the
cap is real but is neither one number nor one mechanism -- below ~500 rows the work is not
cut finely enough to fill the box, above ~30 MB the bytes bind, and in between the kernel
reaches 48-69 Gelem/s, 1.5x what was being quoted as the box's limit. Record and the
leaf/grain arithmetic:
`.todo/artefacts/702-the-parallel-cap-is-the-machinery-or-memory-one-run-decides/README.md`.

Carry one consequence: **a parallel GEMV rate is a property of how the work was cut up**,
not of the machine and not of the weights. The 10x collapse once seen while two lanes shared
a box is `.todo/697`'s mechanism, not a property of anything measured here.

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
skipped with **235** reports. **GB10 certifies `9b10e4f0f`** at the close of B's three-item
lane: 10125 / 0 / 0 / 189 skipped with **237** reports, exit 0, `GpuTest`
included. Both were taken by the ORCHESTRATOR on `develop`, not from any lane's worktree
(rule 4) -- a lane's combination exists nowhere else. The two heads are days of work apart
and are NOT one certification; jointly they establish that no box is red. `d4225aa5` remains
the last head both boxes actually held, and dorian cannot verify B's `am.ik.gpu` /
`eval/LinalgGpu*` / `codegen/jvm/JvmGpuTemplate` drift at all.

**What a run certifies is failures, errors and the REPORT-FILE COUNT -- never the totals.**
A differing report count means a class was DROPPED rather than skipped, which no skip
accounting reveals. The count walked 232 -> 234 -> 235 on dorian and has read 237 on GB10
across two lanes; **the arithmetic has never closed to the unit**, and no prior report SET
still exists to diff against, which is the whole point of the discipline: the next run on
either box diffs the LIST and names what left, because a count that misses by one is exactly
what a dropped class looks like. **The set now survives**: the certifying run's list is
`.todo/artefacts/670-run-a-published-slm-checkpoint/report-classes-gb10-9b10e4f0f.txt`, and
the next certification on either box writes its own beside it and diffs.

Three things a reader needs before comparing two runs:

1. **A total taken before `0e65326b` is not comparable to one taken after.**
   `LispFormatterTest` used to walk `Path.of(".")` and format every `.lisp` under
   `.claude/worktrees/`, so one term of the comparison was how many agents had run on that
   box recently. After that point totals ARE comparable across boxes.
2. **Skips are comparable, and the cross-box difference has been reproduced**: dorian's 283
   against GB10's 189 is the **87** that `.todo/708` derived from seventeen differing
   classes on ONE box. Contamination never reached skips.
3. **A skip count is only a signal against a prior count for the SAME slice.** Its designed
   meaning and its defect meaning are the same integer, and `Tests run` is invariant under
   skipping but not under deletion -- a skipped leg keeps the headline total while removing
   the coverage, which is how `682` was accepted by a run that skipped the part of the suite
   its rename was most likely to break. dorian's 276 -> 283 delta is left UNATTRIBUTED;
   `CiSpecE2eTest` contributes 0 to it, since it reports zero tests when
   `-Drontolisp.binary` is unset and shows up only in the native run.

The argument for taking the run from `develop` rather than a worktree is that it is the run
that found the red: `LispFormatterTest`'s walk raced a scratch file another test writes into
the project root, giving `Tests run: 9425, Errors: 1` while six lanes' worktrees were green
throughout. **Note what the TOTAL did there** -- 9425 against 10093 is the signature, and
point 3 is the case where it is not. Twice, coverage fell into the SEAM between two correct
plans, nobody skipping an assigned step: **a verification owed by one party and skipped by
everyone else is a gap that looks exactly like coverage until someone checks who actually
ran it.** `.todo/709` Part 2 keeps that kind separate from record failures.

## Findings from the run, and where each one now lives

Pointers, not records -- the home is where it gets updated.

- **`483`'s rule is stated wrong in 483**: not "never write a `default`" but **"an arm
  matching two or more permits IS a default, whatever it is spelled"**. In `.kb/vec.md`.
- **`%la-gather-strided` has five readers** and grepping the name finds two. `.todo/687`.
- **Seven sites hand-write the bf16 conversion arithmetic** and only
  `am.ik.rontolisp.BFloat16` is the authority. Census: `.todo/487`'s remainder.
- **`.kb/string-index-cost.md`** records what `690`'s 340x is and is not.
- **A profile names the COST correctly and the CAUSE only as a guess** -- and the two JFR
  sample sets have to be read together, or a native-heavy arm reads as a Java profile. Both
  came out of `718` and its closers; they are in B's lane retrospective below, and the
  mechanics are `.kb/gpu.md`'s.

## Lanes: ONE worker per orchestrator

**Each orchestrator drives ONE lane at a time, serialized: an item completes, is committed
and pushed, and only then does the next start.** What that buys is the thing two lanes cost
-- the surface-accounting overhead in `.todo/709` exists entirely because two lanes on one
box can touch one mechanism without either seeing the other.

Model by difficulty, `effort=high` throughout: **High -> Fable, Medium -> Opus, Low ->
Sonnet.** A dead worker is RESUMED, never respawned. An item the lane's work turns up is
FILED and left for the next lane's planning, never worked recursively.

**The pool is split by the one thing the boxes do not share.** Every item that needs the
DEVICE is B's, because only GB10 can supply one; **every GPU-free item is A's**. That was
the whole partition and it covered the pool exactly until B drained the device side -- see
the end of B's section for what that now leaves open.

### Orchestrator A -- dorian, GPU-free, the model side

A's previous lane closed `697`, `700`, `698`, `686`, `694` and `704` and certified
`a92e205f`. **Three of the six had their premise moved by their own measurement**, which is
the result that outlived them:

- `697` -- the contention trap is a TAIL, not a floor. "One other build costs 10x" did not
  reproduce; two 64-thread decode loops at once does. The thread default moved to
  `min(cpus, max(2, cpus/2))` for a different reason entirely -- the IDLE-box curve bends
  down past half the box (16 threads 9.55 tok/s, 32 9.45, 64 8.70) -- and the rescue the
  item proposed was built, measured a cost at every budget, and dropped.
- `694` -- the second corpus pass was predicted nearly free (fixed-cost) and is
  marginal-dominated: 746 ms fixed against 479 x 74.8 ms, a whole pass. Taken anyway, with
  the reason written down, because the axis is what `692` proved a backend count cannot see.
- `704` -- both quadratic sites are linear now (JVM `read-file-string` 18,164 -> 537 ms on
  2.7 M chars, n-ary `concatenate` 54,197 -> 357) and `examples/llm`'s `read-file-bytes`
  detour still STAYS, the byte path remaining 2x (JVM) to 23x (interpreter, wasm) faster
  because what is left is character `read-sequence` itself. Residue: `.todo/721`.

`700` killed its own second option empirically (a jar with an `Add-Modules` manifest
attribute still throws `ClassNotFoundException` under `java -jar`), and `698` found a wider
defect than the one filed -- a SIMPLE packed float vector's `subseq` threw outright on the
interpreter, so the reported adjustable case was the narrow half. The cards it left are
`.kb/simd-parallel.md`, `.kb/string-accumulate-cost.md` and `.kb/vec.md`'s "The E2E `--simd`
axis", the last of which is `694`'s new `CiSpecE2eTest` axis.

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

B's previous lane closed `723`, `725` and `476`. **Its subject was the device arm's HOST
floor, and it halved the arm twice without touching a kernel**: 51 -> 25 -> 18.5 ms a
forward, so the arm that trailed `--simd --parallel` by 1.9x now leads it (21.6). `723`
hoisted the residency guard out of the typed loops and un-narrowed a conv kernel the loader
had cast to the weight width the file stores at F32; `725` grew the KV cache with the
position reached, which took the per-token upload from 102 MB to 0.74 and moved the CPU arm
as well (90.6 -> 78.4 ms on one `--simd` thread). What outlived them:

- **A profile names the COST correctly and the CAUSE only as a guess.** `718` was right to
  the millisecond about all three DeltaNet functions and wrong about both of its "probably
  because"s; the real causes took a minute to find with a flag that prints the declining
  form (`-Drontolisp.debug.typedlooptrace=true`, which `723` added).
- **A bound one operand of the pair cannot spell is a bound in the wrong place.** `725`
  DECLINED the row-count argument on `vec:matvec` the item proposed -- the value cache is
  transposed and wants a COLUMN bound -- and bounded both products from the caller instead,
  leaving the library surface unchanged.
- **`476` closed as a REFUSAL that its own ordering produced.** Taken last, against the
  corrected step, its "~8% of a step" turned out to be 8% of the JAVA half of a step that is
  93% native: `jdk.ExecutionSample` never sees a thread inside a downcall. Measured in
  isolation the mechanism is 0.7 ns a call on C2 and nothing at all on Graal, which folds
  the whole receiver chain, and `LinalgBlasKernels` had been `static final` all along.
  **Read `ExecutionSample` and `NativeMethodSample` together or a native-heavy arm reads as
  a Java profile.**
- `722` stopped being a prediction and became a bill: eleven lines of `ci-spec` from `723`,
  touching nothing the component path is suspected of, trapped the whole corpus -- before
  AND after `693`'s adapter fix landed with thirty corpus lines of its own that passed.

Everything that lane filed is B's own and is in the table below; what went to A was `722`'s
invoice, recorded there.

**The current lane is the device pool, and the device pool is nearly empty.** Both items are
High. They do NOT stand in each other's denominator -- B-1's arithmetic is the JVM class
output, B-2 is the native binary only -- so for the first time the order is by which one can
be finished rather than by which moves the other's number.

| # | item | difficulty | why here, why now |
| --- | --- | --- | --- |
| B-1 | `726` Q4 on the device: the refusal's trigger (a) has fully fired | High | FIRST because it is a promise already written down and its arithmetic is already assembled: the same 6.73 ms bf16 GEMV is 36% of an 18.5 ms arm where it was 15% of a 45 ms one, so the Q4 ceiling moved from "under 10%" to ~26%. **A refusal computed as a share decays when the denominator does** -- which is exactly why `718` wrote the trigger down instead of closing the question, and why the two items that fired it were not about Q4 at all. It is also the only item in the lane on the checkpoint path. **"Still refused" is as good a close as a build**; `718`'s was |
| B-2 | `727` an FFM downcall costs 2.1 us inside a native image, 230x the JVM | High | Second because the mechanism is inside SubstrateVM rather than inside this repo, so the honest first step is finding out what the 2 us IS before deciding whether we can spend it -- open-ended, and it does not decay. It is B's for a narrow reason only, that its probe binds `libcuda.so.1`; what it COSTS is every FFM surface the native binary has, `--gpu`, `--blas` and `objc:` alike, including a `LinalgBlasKernels.MIN_WORK` threshold chosen on JVM numbers and possibly ~70x wrong in the binary. It is the cost `.kb/gpu.md` has called unexplained since `123`, and `476` is what narrowed it: handle constancy is ruled out, and the control row (the same loop without the call, 32.8 ns against the JVM's 3.6) is what makes it a downcall finding rather than a slow-binary one |

**Not in this lane, and why.** `514` (`LinalgGpuTest` never finishes on an Apple silicon
Mac) is the one device item NEITHER orchestrator can take: it wants a Metal box, and GB10 is
CUDA on aarch64 Linux. Parking it is not a deferral under rule 3 -- no box in this plan can
even fail it. `684` and `696` stay A's for their x64 halves.

**The partition has stopped covering the pool, and that is this design's result.** Device to
B and GPU-free to A was exact while the device sub-pool held items; after B-1 and B-2 it
holds `514`, which no box can run, and nothing else. **What B draws from once it is drained
is not one lane's decision.** The obvious next axis is the other thing the boxes do not
share -- aarch64 against x64 -- which is what leaves `684` and `696` each with a half on the
wrong box. It belongs beside `.todo/709`: co-signed, not adopted unilaterally.

**The one decision still open, and not either lane's to take alone: `.todo/709` is an
explicit DRAFT and needs co-signing or cutting by both orchestrators.** It is process, so
one side adopting it unilaterally is the failure it is written about. It has now outlived
three full lanes, which is evidence about the item rather than about its subject. It is also
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
    recoverable from the file but not the file from the provenance: Hugging Face answers
    `/api/models/<id>?blobs=true` with the LFS sha256, so a checkpoint whose repo path was
    lost is re-identified by matching bytes already held. **The digest and the refusal to
    guess are two independent goods** -- refusing to guess is why an id is VERIFIED, the
    digest is why a guess would have been SURVIVABLE. State the mechanical half first: a
    written repo id reads as known, so nobody queries the manifest, and "do not guess" is
    advice about judgement that the next person under time pressure will violate.
11. **A closer must check for items waiting on an EVENT, not only for items naming its
    number, and no grep finds those.** `.todo/682` was gated on "the first published
    checkpoint that runs end to end"; it fired THREE times unnoticed, because the trigger
    lived in 682 while the people firing it were closing `677` and `678`. What works: when a
    Done section describes a capability arriving for the first time, **grep `.todo/` for the
    CAPABILITY** -- the format, the model class, the surface -- not the number.
12. **A directory-local ignore rule protects by LOCATION, so moving the rule stops
    protecting whatever stayed** -- and what stayed is invisible to the rename precisely
    because being ignored is what kept it out of it. `682` moved
    `examples/llama2/.gitignore` correctly and the next `git add` swept 61 MB onto develop.
    The tree has 18 such files, several covering whole build trees, so this is a standing
    property. The check is one command at the one moment the files are visible: **after
    moving a directory that contains a `.gitignore`, run `git status --porcelain -uall` for
    untracked files at the OLD path before the next `git add`** -- `-uall`, or the residue
    is one collapsed `?? old/` line. The card is **`.kb/directory-rename.md`**, which
    carries the rest: why the exposure needs a PER-FILE rename, and why the fix is free only
    on the box that makes it (untracking DELETES the file for every puller who had it). The
    full account of the other three things that rename broke outside its own diff is
    `git show 97c85518~:.todo/708-the-formatter-corpus-walks-agent-worktrees.md`.

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
  op/element for the unpack. On the device the `718` refusal stands, but **its trigger (a)
  has fired** and the re-measurement is the lane's B-1 (`.todo/726`); the arithmetic is
  `.kb/gpu.md`'s row, not this file's.
