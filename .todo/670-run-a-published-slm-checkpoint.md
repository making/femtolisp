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

The order they were taken in is the one reusable part: `671` needs no new array type, so a
BF16 checkpoint loaded into `#f` BEFORE the bf16 width existed and both readers were
debugged at f32 with the kernels out of the picture.

## What runs today

**A published checkpoint runs, in three formats, no Python and no conversion step.**
Qwen3.5-0.8B from its BF16 safetensors AND from ggml-org's BF16 GGUF, **token for token
identical between the two**; TinyLlama-1.1B-Chat from safetensors and an F16 GGUF, same
forty tokens; stories15M converted to GGUF answers with `run.c`'s own text token for token
-- the one EXTERNAL oracle, and the one that caught a live bug. **On all four backends since
`693`**, the component leg having read past EOF rather than read a shard.

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
cap is real but is neither one number nor one mechanism, and holds the leaf/grain
arithmetic:
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

**dorian certifies `4a0c8f5e9`** at the close of A's six-item lane: 10133 / 0 / 0 / 283
skipped with **237** reports, exit 0 -- the run was taken at `53077edd2` and certifies the
head by rule 8, `git diff --stat` over `src/` being empty. **GB10 certifies `9b10e4f0f`** at
the close of B's three-item lane: 10125 / 0 / 0 / 189 skipped with **237** reports, exit 0,
`GpuTest` included. Both were taken by the ORCHESTRATOR on `develop`, not from any lane's
worktree (rule 4) -- a lane's combination exists nowhere else. The two heads are days of work
apart and are NOT one certification; jointly they establish that no box is red, and dorian
cannot verify B's `am.ik.gpu` / `eval/LinalgGpu*` / `codegen/jvm/JvmGpuTemplate` drift at all.

**What a run certifies is failures, errors and the report-file SET -- never the totals.** The
count walked 232 -> 234 -> 235 -> 237 on dorian while reading 237 on GB10, and the arithmetic
never closed to the unit. It does not have to any more: **the two boxes' report-class lists
are identical, name for name**, so every class is present on both and what differs is only
what each one SKIPS. That is stronger than the discipline was written to get -- a later run
diffs against a list known to be SHARED, so a name that leaves is attributable to the box or
to the change and never to the boxes having always differed. Both lists, and the prediction
of a non-empty diff that this measurement overturned, are
`.todo/artefacts/670-run-a-published-slm-checkpoint/`; each certification writes its own
beside them.

Three things a reader needs before comparing two runs:

1. **A total taken before `0e65326b` is not comparable to one taken after.**
   `LispFormatterTest` used to walk `Path.of(".")` and format every `.lisp` under
   `.claude/worktrees/`, so one term of the comparison was how many agents had run on that
   box recently. After that point totals ARE comparable across boxes.
2. **Skips are comparable, and the cross-box difference is a skip difference, not a class
   difference**: dorian's 283 against GB10's 189 is `.todo/708`'s **87**, derived from
   seventeen classes that differ on ONE box -- classes present on both and skipping
   different amounts, which the identical set above now proves. Contamination never reached
   skips.
3. **A skip count is only a signal against a prior count for the SAME slice.** Its designed
   meaning and its defect meaning are the same integer, and `Tests run` is invariant under
   skipping but not under deletion -- a skipped leg keeps the headline total while removing
   the coverage, which is how `682` was accepted by a run that skipped the part of the suite
   its rename was most likely to break. dorian's 276 -> 283 delta is left UNATTRIBUTED;
   `CiSpecE2eTest` contributes 0 to it, reporting zero tests when `-Drontolisp.binary` is
   unset and showing up only in the native run.

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
  came out of `718` and its closers; the mechanics are `.kb/gpu.md`'s.
- **The corpus's `--simd` axis was running the scalar path on BOTH of its legs.** Every
  `vec:` / `linalg:` case in `ci-spec.yaml` was 2-6 elements, under every length gate
  (THRESHOLD 128, MATVEC_ROW 16, MATVEC_ACC 32), so `694`'s new axis doubled the legs of
  cases that could not reach the code it was added to cover. `705`'s two decode cases are
  the first shapes in the corpus that clear the gates. Rule 6, on rule 6's own instrument;
  the record is `.kb/vec.md`'s "The E2E `--simd` axis".
- **A pin counts only in the lane that runs it** -- `705`, and rule 13.
- **The examples suite compiled components without ever running one.** `examples.yaml`'s
  `wasm-component` token was compile-only; `693` added `wasm-component-run` (build
  `--component --optimize`, run under `wasmtime`, compare output) and moved twenty entries
  onto it. `wasm-component` now marks only what cannot be run that way.

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

A's previous lane closed `720`, `719`, `703`, `714`, `693` and `705` and certified
`4a0c8f5e9`. Its subject was **which specialized array type survives an operation**, four
items on one mechanism -- and in five of the six the defect was not the one the title named.

- `720` -- only TWO backends had the hole. `--no-gc` types the widths apart and answers
  `incompatible types F32VEC and F64VEC` at COMPILE time, so "a speed flag must not decide
  whether a program runs" needed the JVM and wasm-GC only. Each declines in the shape its
  call site allows: the JVM already had a fallback branch (the module-absence degrade),
  wasm-GC has none, so there the decision moved INSIDE the helper and forwards to the
  scalar defun.
- `719` -- `subseq` was not the defect. `%array-alike`, the allocator `subseq` and
  `copy-seq` share, decided by the RUNTIME MARKER (JVM: `long[]` only; wasm: the integer
  array types) instead of by the element type the source answers. Fixing that DELETED two
  duplicate paths rather than adding one, and the completion test was deleting the
  `expectedByBackend` in which `698` had RECORDED the wrong answer.
- `714` -- one surface, three entry points: the shared lowering, an interpreter
  `concatenate` that does not go through it, and `#'concatenate` as a first-class value
  dispatching separately -- with both compilers needing to be told the helper is reachable.
- `693` -- the `--component` leg does not trap on a SHARDED read; it traps on reading past
  EOF TWICE. Preview 1 keeps answering zero bytes, WASI 0.3 notifies the writable end's drop
  once and the host traps on the next `stream.read`. Minimal case: three `read-line`s on a
  one-line file. Its second half outweighs its fix -- see the findings above.
- `703` -- **the item asked for an error the upstream oracle does not signal.** SBCL answers
  a `t` array for `'not-a-type`, diagnosing only at compile time as a STYLE-WARNING, and
  `deftype` may register AFTER the reference, so "this names no type" is not yet a fact
  where `make-array` is compiled. 22 `:element-type` sites in the bundled corpus
  (`'fixnum` 12, `'bit` 5, `'(unsigned-byte 64)` 5, across alexandria, cl-ppcre, ironclad,
  jzon, chipz, md5, cl-base64, fast-io) are legal upgrades a signal would break. The weak
  form has no oracle either: judging "names no type" wants a set of type names this project
  does not have, and the nearest switch omits `BIT`. Closed as a DECISION, in
  `.kb/array-literals.md`; the post-condition stays the caller's, as the item's own Do 3 had it.
- `705` -- **its premise was false and the correction is rule 13.**

**The current lane is the two instruments the checkpoint path is measured with, then the
error surface that three separate items describe from three sides.** The first half is
ordered by what the rest of the lane rests on; the second half's order is forced, and taking
it the other way round moves the lane's own headline for the wrong reason.

| # | item | difficulty | why here, why now |
| --- | --- | --- | --- |
| A-1 | `722` the WASM component backend traps on a `ref.cast` that a one-line source edit MOVES | High | FIRST because every pin this lane or any other adds is behind it. The corpus is not one case FROM the cliff, it is AT it: `723`'s eleven lines trapped the whole corpus and its case was dropped rather than shipped red, `693`'s thirty lines passed, and the passing and trapping adapters are byte-identical. **A cross-backend pin an unrelated later case can turn red is not a pin** -- the lane's instrument, in the way `717` was B's |
| A-2 | `724` `llm.lisp`'s `achieved tok/s` is sampled tokens over the prompt's clock | Medium | Second because it is the other instrument, and the one this file quotes: every `tok/s` row in `examples/llm/README.md` reads 1.7-2.1x low by a factor that DIFFERS per arm and per prompt length, so the rows compare with each other and with nothing else. The fix is Low and the blast radius is the record. **The `--gpu` rows can only be re-measured on GB10** -- A takes the harness and every GPU-free row, and step 3's "state the bias, dated" covers the rest until B's box is free |
| A-3 | `701` diff every checkpoint's own `chat_template` against the hand-written one | Low | Third and cheap, while the checkpoint path is in hand. It is A's for a mechanical reason that outranks the item's note that the other orchestrator offered it: dorian holds all six models the script walks, GB10 would re-fetch them (rule 9's per-box decay, in the direction that skips a needed step). One positive case is already waiting to be explained -- both Qwen models answer with no think-aloud preamble where `llama-cli` still thinks aloud at `--reasoning-budget 0`, with tokenization ruled out since `495c4a6b` |
| A-4 | `680` argument validation throws a raw Java exception no Lisp handler can catch | High | Opens the second half as the CAUSE the other two describe. An `IllegalArgumentException` out of an argument check passes straight through `handler-case` and kills the run; the ANSI suite bills it at 660 tests under one message, 101 more under `setf does not support place`, plus every `IndexOutOfBoundsException` out of `aref` |
| A-5 | `681` the ANSI report drops failing tests from its own denominator | Low | Immediately after `680`, and the order is forced by what the report would otherwise show: those forms count into the reason table but not into `pass`/`fail`/`error`, so a `680` fix arrives as a SMALLER LOSS COUNT instead of as passes. The honest headline is 45.5%, not the 50.4% in bold, and three smaller accounting defects sit in the same file |
| A-6 | `715` ANSI conformance: what the suite says to fix next | Medium | Last, because it is a READING of a report that both items before it change, and ranking gaps against a denominator known to be wrong is the one thing it must not do. Its own "two failure shapes worth investigating as bugs, not as gaps" IS `680`, so it inherits whatever that measurement decides |

**A's pool, not in the lane.** All GPU-free, none blocking the checkpoint path:

- `684` (Low) and `696` (Medium) each hold an **x64 half that is A's**, every `.todo/488`
  number behind them being aarch64. Neither is on the width chain's critical path, and the
  partition note at the end of B's section is about them.
- `721` (`704`'s residue: character `read-sequence` costs ~1.2 us/char) wants a quiet box
  and does not decay.
- `679` (High -- `'pi` reads as a double, so a read-time constant is not quotable) is the
  third sibling of `680` and `681` and is deliberately NOT in the lane: it is a reader
  defect that shares their report and none of their mechanism, and the lane already carries
  two Highs.
- `597` (the other four `geom:` model readers have no interpreter native), `689`
  (`jvm-export` handles do not carry bfloat16), `699` (one UTF-8 lead-byte table, two
  hand-written copies), `695` (the `.kb` compaction follow-up).

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
four full lanes, which is evidence about the item rather than about its subject. It is also
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
   tests counted backends and never `--simd` on each (`.todo/694`). **The instrument is not
   exempt**: every `vec:` / `linalg:` case in the corpus was 2-6 elements, under every
   `--simd` length gate, so `694`'s new axis doubled the legs of cases that could not reach
   the code it exists to cover, until `705` added a shape that clears them.
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
    written repo id reads as known, so nobody queries the manifest.

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
    carries the rest, and the full account of the other three things that rename broke
    outside its own diff is
    `git show 97c85518~:.todo/708-the-formatter-corpus-walks-agent-worktrees.md`.

13. **A pin counts only in the lane that RUNS it, so "no test asserts X" is a claim about
    the lane and not about the assertion.** `705` was filed, and planned, as "no suite
    asserts on decoded text for any model" while `examples/examples.yaml` had matched forty
    tokens against run.c's own output on four backends since before the filing -- in
    `ExamplesE2eTest`, which is `needs: release`, which `./mvnw test` skips, and which is
    the one job allowed to be red. **Name the job an assertion runs in before concluding
    there is none**, and when the answer is "a job the change does not run", the work is to
    move the pin, not to write it again.

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
