# 670. Run a published SLM checkpoint: what Hugging Face ships, loaded as shipped

Difficulty: High (the umbrella; the children are sized individually)

Filed 2026-09-03 from the re-verification of `.todo/482` (`bfloat16`), which closed
2026-09-08 with the width's account in `.kb/bfloat16.md` ("The width's account").

**The goal: a small language model that someone downloaded from Hugging Face runs on
rontolisp from the file they downloaded** -- no Python, no `export.py`, no conversion step
outside the language. The models people actually run are published as **safetensors**
(bf16, one JSON header and raw tensors) and **GGUF** (F32 / F16 / BF16 / Q8_0 / Q4_K_M,
tokenizer and hyperparameters in the same file).

**This file is the plan and the rules. Every number it quotes has a child that owns it**
(rule 9) -- go there to change one.

## What the measurements decided, width by width

| width | verdict | where |
| --- | --- | --- |
| **bf16** | THE width. 1.5-2.1x f32 on one thread (Graal / C2), 1.6x on 20; widening exact; every checkpoint is in it | `.kb/bfloat16.md`; `482` and 483-490 all closed |
| **IEEE f16** | not a width -- a **load-time conversion** into `#f` / `#bf16`. A fused f16 GEMV is 0.30-0.58x on either JIT | `.todo/671` |
| **Q8_0** (32 int8 + a scale) | a **read-only weight matrix** type with an integer-dot GEMV: 1.4-1.6x f32 on one thread under Graal, 1.7-1.9x under C2, 2.2-3.3x on 20, a quarter of f32's bytes. On the device since `728`: the kernel is the CPU contract's BITS, 5.3 ms of GEMV a forward against bf16's 7.7, the forward 13.9 against 16.7 (1.20x), tokens byte-identical with the flag on and off | CPU: `672`, `706`. Device: `728`. The row gather: `732` |
| **Q4_0 / Q4_K** | not a CPU item: the nibble unpack is ALU-bound at 5.7 GB/s (1.1x f32 for 8.5% error). **Refused on the device too, twice** -- `718` on a decode profile, then `726` at the forward's own shapes. Behind `728`'s Q8_0 its increment is bounded by the byte ratio, ~2.7 ms of a 13.9 ms forward, at 8.5% error against 0.75%, so the refusal's reason is now ORDER, not size | `.kb/gpu.md`, "No Q4_0 / Q4_K weight width"; kernel rows in `.todo/artefacts/123-gpu-acceleration/README.md` |

Two facts under all four: **the width is bandwidth, not fitting** -- 4.4 GB of f32 fits an
8 GB laptop -- and **every kernel number is JIT-dependent**: the spike's fused kernel fell
to 0.20x under C2 from an inlining cliff, so `.todo/488` takes its numbers under both JITs.

## Children: all closed

`671` (bulk widening), `673` (GGUF reader), `674` (byte-level BPE), `675` (safetensors +
`config.json`), `676` (the forward pass as a table of layer kinds), `677` (Gated DeltaNet),
`678` (LFM2 gated short-conv), `672` + `706` (the Q8_0 matrix and its integer dot), `489`
(the model rungs at f32 and bf16), `490` (bf16 on the device), and `482` (the width
umbrella) with `746` / `745` / `689` / `696` / `732` its remainder. Each owns its own
numbers (rule 9); the measured rungs live in `examples/llm/README.md`, the closing accounts
in `.todo/history/2026-09.md`.

The order they were taken in is the one reusable part: `671` needs no new array type, so a
BF16 checkpoint loaded into `#f` BEFORE the bf16 width existed, and both readers were
debugged at f32 with the kernels out of the picture.

## What runs today

**A published checkpoint runs, in three formats, no Python and no conversion step.**
Qwen3.5-0.8B from its BF16 safetensors AND from ggml-org's BF16 GGUF, **token for token
identical between the two**; TinyLlama-1.1B-Chat from safetensors and an F16 GGUF, same
forty tokens; stories15M converted to GGUF answers with `run.c`'s own text token for token
-- the one EXTERNAL oracle, and the one that caught a live bug. **On all four backends
since `693`.** Splitting a quantized weight matrix needs no scratch file since `732`.

**The path is documented as a path, not only as operators.** `gguf`, `safetensors`,
`checkpoint` and `tokenizer` are listed in the Functions index and the guide
`doc/*/guides/running-a-checkpoint.md` walks container -> metadata-only read ->
the checkpoint's own tokenizer -> the widths that load -> the chat template (`733`).
`examples/llm/` stays the worked program and is linked once, not restated.

**The device arm leads.** `--gpu --simd` is 18.5 ms a forward on Qwen3.5-0.8B against
`--simd --parallel`'s 21.6, where it trailed 1.9x two lanes ago; neither change that moved
it touched a kernel (`723`, `725`). The rows are `examples/llm/README.md`'s -- **and every
`tok/s` printed before `724` reads 1.7-2.1x low**, by a factor that differs per arm and per
prompt length, so those rows compare with each other and with nothing else.

**The parallel leg is bound by the parallel machinery, not by DRAM** -- distribution,
barriers and per-row dispatch -- and **how much a model pays depends on how its work is cut
up**: Qwen3.5's Gated DeltaNet does 576 small 128x128 GEMVs per token against LFM2.5's ~30
big matvecs, so the one paying more dispatch peaks earlier. The signature is a
model-specific SATURATION POINT carried by within-model scaling only, tok/s over tok/s. **A
cross-model GB/s comparison is NOT evidence here**: it divides by an activation-blind
parameter-count estimate that omits exactly Qwen3.5's recurrent state. The evidence is
`489`'s -- **the knee did not move when the bytes halved**, six models and two widths, where
a DRAM cap would have pushed it outward. A third route, one rate read at two sizes, was
WITHDRAWN by `702`: the rate over shape is a HUMP and those were two points on it. `702`'s
sweep says the cap is real but is neither one number nor one mechanism, and holds the
leaf/grain arithmetic in
`.todo/artefacts/702-the-parallel-cap-is-the-machinery-or-memory-one-run-decides/README.md`.

Carry one consequence: **a parallel GEMV rate is a property of how the work was cut up**,
not of the machine and not of the weights.

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

**What a run certifies is failures, errors and the report-file SET -- never the totals.**
The count walked 232 -> 238 on dorian while reading 237 on GB10 and the arithmetic never
closed to the unit. It does not have to: **the two boxes' report-class lists are identical,
name for name**, so every class is present on both and what differs is only what each
SKIPS. A later run therefore diffs against a list known to be SHARED, and a name that
leaves is attributable to the box or to the change, never to the boxes having always
differed. Each certification writes its own list beside the others in
`.todo/artefacts/670-run-a-published-slm-checkpoint/`.

- **dorian certifies `20d8ac979`** at the close of A's lane before last: 10159 / 0 / 0 / 290
  skipped, **238** reports, exit 0. The set gained exactly one name against both earlier
  lists -- `AnsiChapterRunnerTest`, the class `681` added -- which is the outcome the
  discipline is for: a diff of one, attributable to a named change.
- **GB10 certifies `b6d0ea513`** at the close of B's two-item lane: 10128 / 0 / 0 / 189
  skipped, 237 reports, exit 0, `GpuTest` included (59 tests, 560.7 s).
- **dorian does NOT certify `8b3adb1e8`** at the close of A's six-item lane (`746`, `745`,
  `689`, `696`, `732`, `482`): 10184 / 0 / **1 error** / 290 skipped, 238 reports.
  `JvmClassShakerCorpusTest` errors with a `StackOverflowError` whose entire stack is
  `Test._append`. **The error is not in the tree**: the same commit is green in a fresh
  worktree, and red in the main checkout with the lane's `ci-spec.yaml` reverted. The corpus
  walks the project root with `**`, dorian's root now holds 9991 of its 10375 `.txt` files
  inside other agents' worktrees, and `append` recurses once per element. Filed as
  `.todo/748` (the walk) and `.todo/749` (the recursion); they open A's next lane. Every
  other class in the run is green, and **the report-class list is identical to
  `20d8ac979`'s, name for name** -- so the red cannot be a dropped class, a renamed one or a
  skipped leg, which is what left the box itself as the only candidate.

The heads are lanes apart and are NOT one certification; jointly they establish that no box
is red for a reason in the tree, and dorian cannot verify B's `am.ik.gpu` /
`eval/LinalgGpu*` / `codegen/jvm/JvmGpuTemplate` drift at all. Both are taken by the
ORCHESTRATOR on `develop`, never from a lane's worktree (rule 4) -- a lane's combination
exists nowhere else, and rule 16 is why a worktree is not a substitute even when it is
convenient.

Three things a reader needs before comparing two runs:

1. **A total taken before `0e65326b` is not comparable to one taken after.**
   `LispFormatterTest` used to walk `Path.of(".")` and format every `.lisp` under
   `.claude/worktrees/`, so one term of the comparison was how many agents had run on that
   box recently. `.todo/748` is that defect re-introduced in Lisp, inside the corpus.
2. **The cross-box difference is a SKIP difference, not a class difference**: dorian's 290
   against GB10's 189 is `.todo/708`'s 87, from seventeen classes present on both that skip
   different amounts. Contamination never reached skips.
3. **A skip count is only a signal against a prior count for the SAME slice.** Its designed
   meaning and its defect meaning are the same integer, and `Tests run` is invariant under
   skipping but not under deletion -- a skipped leg keeps the headline total while removing
   the coverage, which is how `682` was accepted by a run that skipped the part of the suite
   its rename was most likely to break.

The argument for taking the run from `develop` is that it is the run that FINDS the red --
twice now the red was visible nowhere else. `LispFormatterTest`'s walk raced a scratch file
another test writes into the project root while six lanes' worktrees were green throughout;
`748` is red in the checkout that carries worktrees and green inside every one of them.
Twice, coverage fell into the SEAM between two correct plans with nobody skipping an
assigned step: **a verification owed by one party and skipped by everyone else is a gap that
looks exactly like coverage until someone checks who actually ran it.**

## Findings, and where each one now lives

Pointers, not records -- the home is where it gets updated.

- **`483`'s rule is stated wrong in 483**: not "never write a `default`" but **"an arm
  matching two or more permits IS a default, whatever it is spelled"**. `.kb/vec.md`.
- **`%la-gather-strided` has five readers** and grepping the name finds two. `.todo/687`.
- **`bfloat16` was a float ALIAS in the `subtypep` lattice, not an EDGE**, from the day the
  width landed: `(subtypep 'single-float 'bfloat16)` answered T against
  `(typep 1.0 'bfloat16)`'s NIL, and a COMPUTED pair on the compile paths answered NIL
  against everything, itself included -- the runtime universe derives edges and hand-lists
  aliases. Found closing `482`, the one arm the umbrella specified and no child owned.
  `.kb/declarations-type-checks.md`.
- **The bf16 conversion arithmetic census**: not seven sites and not the grep's twelve --
  `.kb/bfloat16.md`, "The conversion arithmetic census".
- **`append` recurses once per element on all four backends**, so a long first argument is a
  `StackOverflowError` rather than a slow call. `.todo/749`.
- **A profile names the COST correctly and the CAUSE only as a guess**, and the two JFR
  sample sets must be read together or a native-heavy arm reads as a Java profile.
  `.kb/gpu.md`.
- **The corpus's `--simd` axis ran the scalar path on BOTH legs** until `705`: every `vec:` /
  `linalg:` case was 2-6 elements, under every length gate. `.kb/vec.md`, "The E2E `--simd`
  axis".
- **The examples suite compiled components without ever running one** until `693` added
  `wasm-component-run`.
- **`.kb` is at ~42% of its pre-compaction size and that is the resting size** -- the
  remaining ratio spread measures how terse each file's baseline was, not how far it is from
  card shape. `695`, recorded dated in `.kb/README.md`.

## Lanes: ONE worker per orchestrator

**Each orchestrator drives ONE lane at a time, serialized: an item completes, is committed
and pushed, and only then does the next start.** What that buys is the thing two lanes cost
-- the surface-accounting overhead `.todo/709` was written about (cut 2026-09-10) exists
entirely because two lanes on one box can touch one mechanism without either seeing the
other. **Documentation-only items are
the stated exception and run BESIDE the lane** -- rule 14.

Model by difficulty, `effort=high` throughout: **High -> Fable, Medium -> Opus, Low ->
Sonnet.** A dead worker is RESUMED, never respawned. An item the lane's work turns up is
FILED and left for the next lane's planning, never worked recursively.

**Two questions, and only one of them is the box.** WHO may take an item is split by the one
thing the boxes do not share: an item that needs the DEVICE is B's, and a GPU-free one can be
A's. **WHAT belongs in a lane is a different question, and it is the umbrella's subject** --
running a published checkpoint: the container readers, the tokenizer, the narrow widths and
the kernels that stream them, the four backends they must agree on. Answering the second
question with the first is how this file once carried a printer margin, four ANSI operator
families and a `cons` set-operation row in A's lane -- every one of them real work, none of
them about a checkpoint (rule 15).

### Orchestrator A -- dorian, GPU-free, the model side

A's previous lane was **the narrow width's remainder**, and closed all of it: `746`, `745`,
`689`, `696`, `732` and the umbrella `482` itself. Three boundaries, one kernel, one close
-- and in four of the six **the item's own statement of the problem is what turned out to be
wrong.**

- `746` -- the census is not seven sites and not the grep's twelve: one authority, three
  false positives, five emissions a backend cannot route through `BFloat16`, one avoidable
  copy, one deliberate. Checking those five against the authority in the NaN direction is
  what found a live defect -- two fused `--simd` kernels carrying a quiet-bit formula that
  was fixed the SAME DAY they landed, 126 of 65536 patterns wrong. **A fix dated the same
  day as the code it never reached is invisible to any comparison by date.**
- `745` -- of the bulk pair's four arms only two convert; `:bfloat16` <-> `#bf16` is a
  pattern copy, so there was no rounding to add. The defect found alongside belonged to the
  three widths already shipped: `_narrowFloatBits` asked `_fvLength` for the source's
  element count and its rank-n arm rejects multidimensional arrays, so a rank-2 source threw
  on the compile paths and narrowed fine in the interpreter. **A divergence found while
  adding a fourth arm was owned by the three that were already there.**
- `689` -- `Width`'s third member is not a third branch. bf16 spends TWO header slots per
  dimension (a `short` tops out at 32767), so the eight readers that spelled `1 + rank`
  misread it, and the fix was to route them all through `dimAt` / `dataOffset`. **A new
  member with a different header shape is a change to every reader.**
- `696` -- **the premise was overturned by its own measurement.** Narrowing DOES vectorize:
  a branch-free lane form is 1.4-3.2x the scalar loop and agrees on all 2^32 patterns, and
  the composite kernel is 2.3-2.7x the scalar route. The prediction was right about the
  shape it imagined (lane load, scalar store: 0.89-1.07x) and wrong that the shape was
  forced. **A refusal justified by a predicted implementation dies the moment a different
  implementation is measured.** The implementation is `.todo/747`.
- `732` -- the item proposed a contiguous row range; the driving case (`examples/llm`'s
  `split-gated-q`) interleaves query and gate per head, so a range needs a join the item
  never mentioned. It shipped as a row GATHER instead, one `System.arraycopy` per row.
  **A primitive designed from the statement of the problem rather than from its caller gets
  the wrong arity.**
- `482` -- closing the umbrella was not a formality, exactly as its row predicted. The audit
  found the one arm the umbrella SPECIFIED and no child owned: the type registered as a
  fifth alias of `float` instead of a fourth subtype (Findings above). **An umbrella's own
  specification is the one thing none of its children tests.**

**The current lane was the instrument, then the width's last kernel.** A's box could
not certify anything until `748` landed; both instrument items have now closed, and
the suite is green on dorian again (10342 / 0 / 0 / 290 skipped at `ee30f4c20`).
The lane proceeds at A-3.

| # | item | difficulty | why here, why now |
| --- | --- | --- | --- |
| A-1 | `748` the corpus's wild-pathname case walks the whole project tree | Low | CLOSED 2026-09-09 (`2f5cafb07`, bounded walk; file removed, history recorded). It was the precondition of the lane, and the instrument is restored |
| A-2 | `749` `append` recurses once per element on every backend | Medium | CLOSED 2026-09-10 (`ee30f4c20`): the copy is iterative in `Environment.appendTwo`, both `RuntimeBuilder` `_append`s and both mapcan accumulators, plus the mapcon expansion's left fold the census turned up; pinned at 100k in ci-spec and per-backend unit tests |
| A-3 | `747` the element-wise `bfloat16` `vec:` kernels | High | Third: the one bf16 arm still declined, and the only lane item whose measurement is already DONE (`696`, with the harness and the lane form written out). What is left is design -- which operand pairings are admitted, what width a result takes, which of ~40 members mirror -- so it is High for the decisions, not for the numbers. Directly on the subject: it is how a checkpoint's weights are streamed at the width they are published in |
| A-4 | `731` the wasmtime landing-pad report and the toolchain floor | Medium | Fourth: on the subject by descent, the argument `722` earned -- a cross-backend pin that an unrelated later case can turn red is not a pin, and the checkpoint path is pinned on four backends. Half of it is a person's action (filing upstream is an external submission), so it is last: the half this lane can finish is the floor, and **the narrowing question is answered by the PIN, never by the version** |

**A's pool for THIS umbrella.** GPU-free and on the subject:

- `721` (Medium) -- a character `read-sequence` costs ~1.2 us/char. Adjacent rather than on
  the path: the checkpoint readers use the BYTE and packed transfers, not the character one.
  It is here because it wants a quiet box and does not decay, and **it is the item to drop
  the next time a lane has to choose** -- this is the second lane it has been passed over
  for, which is evidence about the item rather than about the lanes.

**Not this umbrella's, and not in A's pool.** Every one is real work and GPU-free, and none
is about running a checkpoint. They belong to their own tracks and any lane may take them;
670 should not own them: `041` (the printer's right margin), `735` / `736` / `739` / `740` /
`741` / `742` / `743` / `744` (the ANSI ranking `715` produced -- `715` is its own standing
reading and is not this file's either), `679` (`'pi` reads as a double), `597` (the four
`geom:` MODEL readers -- solid models, not checkpoints; the name is the whole reason it
drifted in here), `699` (the UTF-8 lead-byte table), `737` (the `.kb` index pin), `684` (the
f64 `--simd` GEMV row -- f64 is not a model width, so its x64 half is A's by box and nobody's
by subject).

### Orchestrator B -- GB10, the device

B's previous lane closed `726`, `727` and `728`. What outlived them:

- **A refusal re-taken on a measurement can survive and still move.** `726` kept Q4 refused
  and changed the REASON from size to ORDER: the cheaper width was in FRONT of the one being
  argued about, and only a measurement at the real shapes could see it.
- **A kernel that misses bandwidth is not always the weight side.** `726`'s f32-`x` kernels
  fell short on the `x` STRIDE, not the weight layout; the integer-dot shape the CPU
  contract already computes was fastest at every shape.
- **`727` refuted its own framing twice.** The 2 us is not the boundary and not `libcuda`:
  the same address through SubstrateVM's `@InvokeCFunctionPointer` is 10.7 ns in the SAME
  image. It is `Target_java_lang_invoke_LambdaForm.forceInterpretation()` returning `true`
  -- ~1.7 us + ~0.4 us per argument, which predicts all five shapes we issue.
- **A threshold is a property of the RUNTIME, not of the kernel.** `--blas`'s `MIN_WORK = 64`
  was picked on JVM numbers and was wrong in the binary by three orders; no test could see it
  because every test ran on the JVM.
- `728` shipped the Q8_0 GEMV **as the CPU contract's bits**, so the completion test was a
  decline disappearing rather than a number improving.

| # | item | difficulty | why here, why now |
| --- | --- | --- | --- |
| B-1 | `729` the binary's downcalls through SubstrateVM's own AOT route | High | **CLOSED 2026-09-10, half built and half refused on a measurement** (`.kb/native-downcalls.md`). The seam is real and payable: a `-Pnative` source set (`src/native/java`, `org.graalvm.sdk:nativeimage` provided) substituting the four CBLAS products with `@InvokeCFunctionPointer` calls on the addresses the FFM bind records -- 11-16 ns for the CUDA shapes and ~90 ns for the BLAS ones (three `PinnedObject`s) against 2.1-7.3 us through the handle -- and the binary's `--blas` crossover is the JVM's again, so `MIN_WORK` is ONE number (64). The CUDA half was measured and NOT widened: the binary's `--gpu` forward on Qwen3.5-0.8B is the interpreter's, 0.12 tok/s (8.3 s), so the ~5 ms of driver interpretation is 0.06% of it; objc/Metal are unmeasurable here. The certification run (`d0daa93f0` merged, 10331 / 2 failures / 0 errors / 189 skipped, 240 reports) found two GB10-only reds that reproduce WITHOUT the change and are filed as `756` (aarch64 `(exp 1)` digit) and `757` (`RontoComplex` missing from a standalone `--gpu` class) -- rule 4 again. Was: the lane, and open-ended: `727` left the measurement done and the COST accepted rather than the design. 10.7 ns against 2-7 us on the same address in the same image, ~5 ms of a decode forward's ~1300 driver calls, and a `--blas` floor that exists only to pay for it. What is unsettled is the SHAPE -- a `-Pnative` source set substituting the binding halves of `am.ik.gpu.CudaDriver`, `eval/LinalgBlasKernels` and `am.ik.objc`, one interface method per shape across 45 CUDA + 6 BLAS + the objc table, against core libraries that import nothing. **The honest first step is deciding whether that seam is payable, and "not worth it" is a close** |
| B-2 | `480`'s remainder -- the `--simd` GEMV accumulator chain, audited to a close | Medium | Second, and a CLOSE before it is a build: the four independent accumulators landed 2026-09-03 in all four `--simd` implementations, `matvecRowsBf16` carries the same two constants, and `488`'s README withdrew its 0.80x / 1.02x tables -- the 1.6x headline reproduces against the SHIPPED kernels (GB10 1.32-1.49x Graal, 1.81-2.00x C2 at 4096x4096; x64 1.63-1.85x). So `.kb/bfloat16.md`'s "`.todo/480` (the one-thread 1.6x waits on its accumulator count)" is STALE, and correcting it is the first move. What is actually left is the item's own "What this is NOT verified on" list: `Solo.java`'s GB10 numbers, still to be taken; columns 16-31, the single-chain path no example reaches; and a head dimension other than 48 -- **which `489` satisfied on 2026-09-06, when Qwen3.5-0.8B (`head_dim` 128) ran end to end, and nobody noticed. That is rule 11 in its own file rather than someone else's.** GB10 is where every number in the item was taken, which is what puts it here rather than in A's pool |

**The device pool did not refill this time.** Two lanes ago both closers filed a device
successor and the partition held on that; `728` filed `732`, which was GPU-free and on this
umbrella's subject, so it went to A and has closed. B's second item is therefore drawn from
the WIDTH's remainder rather than from the device: `480` needs no GPU, but every number in
it was taken on GB10, and it is the only bf16 item that is not A-3. The
**aarch64/x64 axis** is what to settle next rather than wait on, and after rule 15 it is
smaller than it looked: only `747` carries a half of it that this plan is about (the narrow
width's element-wise kernels, now A-3, whose x64 measurement `696` already took), and
`684`'s f64 GEMV row belongs to its own track.

**`.kb/bfloat16.md` is named by both lanes at once, so rule 14's per-file exclusion applies
to it and not only to `.kb/error-handling.md`.** B-2's first move edits the file A-3 writes
its own arm into. Rule 7 decides the order: B says the stale line to A before writing it,
and whichever lane is IN the file takes the edit.

**Not either lane's.** `730` (report the SVM findings upstream) is finished as writing and
needs a person to post it. `514` (`LinalgGpuTest` never finishes on Apple silicon) and `516`
(a variadic `objc:send` selector crashes the process) both want a **macOS box**, which
neither dorian nor GB10 is -- parking those is not a deferral under rule 3, since no box in
this plan can even fail them.

**That decision was taken on 2026-09-10, above both lanes: `.todo/709` is CUT.** The draft
asked to be co-signed into `.kb` or cut, and it had outlived seven full lanes without either
happening -- which was already evidence about the item rather than about its subject. Cut is
the honest reading of that: a process card nobody reached for in seven lanes is not a
practice, and landing it late on one orchestrator's say-so is the exact failure it was
written about. What it held stays where it already worked: Part 3's three practices were in
use on both sides before the draft existed and are unaffected, and the mis-record procedure
and the reading disciplines go **nowhere** rather than into `.kb` -- the alternative the
draft itself named. The text is recoverable in full from the commit that removed it.

## Standing rules this run earned, in the order they cost the most

Cited by number from other items -- **the numbering is fixed.**

1. **Only the closer can write back a dependency.** Six items closed in one day and twelve
   open todos still read as blocked by them that afternoon. The grep for items naming the
   number belongs beside the history row in the close procedure.
2. **A count an item wrote down is not a completion test.** A stale dependency line delays a
   start; **a stale count fakes a finish.** Start an audit from the grep, never the number.
3. **Sort every "Remaining" into blocked / not-done / deferred.** Only the first is a real
   remainder; the second is unstarted work in a blocker's clothes; the third evaporates
   without an owner. Two of nine were truly blocked.
4. **One session runs the full suite on `develop`, the other runs the GPU legs.** Four reds
   now have been invisible from every lane's own worktree.
5. **Never two device-touching runs at once, separately from who owns what.** `./mvnw test`
   includes `GpuTest`, so a full suite IS device-touching. **Ownership says who takes a
   result; exclusion says what may run at once** -- fusing the two produced a
   self-contradictory instruction to one lane.
6. **A suite can hold a defect invisibly while every case sits on one side of its condition,
   and the half that looks more exhaustive is the half that hides it.** Three in one day,
   including `692` against a `671` that closed claiming all four backends while its tests
   counted backends and never `--simd` on each (`694`). **The instrument is not exempt**:
   the corpus's own `vec:` cases were all under the `--simd` length gates until `705`, and
   every `append` it runs was under the recursion depth until the box grew (`748`, `749`).
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
   checkpoints are gone" while `.todo/677` carried the correct paths, and two lanes were sent
   to re-download 12 GB that was on disk. **A restated fact also decays PER BOX**, and that
   direction is worse: it skips a needed re-fetch rather than repeating an unneeded one.
10. **Record a checkpoint's SIZE and sha256 beside its path**, because provenance is
    recoverable from the file but not the file from the provenance: Hugging Face answers
    `/api/models/<id>?blobs=true` with the LFS sha256, so a checkpoint whose repo path was
    lost is re-identified by matching bytes already held. **The digest and the refusal to
    guess are two independent goods.** State the mechanical half first: a written repo id
    reads as known, so nobody queries the manifest.
11. **A closer must check for items waiting on an EVENT, not only for items naming its
    number, and no grep finds those.** `.todo/682` was gated on "the first published
    checkpoint that runs end to end"; it fired THREE times unnoticed. What works: when a Done
    section describes a capability arriving for the first time, **grep `.todo/` for the
    CAPABILITY** -- the format, the model class, the surface -- not the number.
12. **A directory-local ignore rule protects by LOCATION, so moving the rule stops protecting
    whatever stayed** -- and what stayed is invisible to the rename precisely because being
    ignored is what kept it out of it. `682` moved `examples/llama2/.gitignore` correctly and
    the next `git add` swept 61 MB onto develop. The tree has 18 such files. The check is one
    command at the one moment the files are visible: **after moving a directory that contains
    a `.gitignore`, run `git status --porcelain -uall` for untracked files at the OLD path
    before the next `git add`**. The card is `.kb/directory-rename.md`.
13. **A pin counts only in the lane that RUNS it, so "no test asserts X" is a claim about the
    lane and not about the assertion.** `705` was filed as "no suite asserts on decoded text"
    while `ExamplesE2eTest` had matched forty tokens against run.c's own output on four
    backends since before the filing -- in a job `./mvnw test` skips. **Name the job an
    assertion runs in before concluding there is none**, and when the answer is "a job the
    change does not run", the work is to move the pin, not to write it again.
14. **A documentation-only item does not contend for the lane's serialization.** Four closed
    beside the lane with no interaction (`733`, `734`, `738`, `695`), because serialization
    exists to stop two workers touching one MECHANISM and a doc-only item touches none. The
    one near-collision was `.kb/error-handling.md`, wanted by `695`'s identifier audit and by
    `680`'s new section at the same time, and it was avoided by naming that file to the doc
    worker as off-limits. **So the exclusion is per-FILE and has to be stated when the lane
    is designed, not discovered when it bites** -- and "documentation-only" means it, since
    an item that must MEASURE to write the doc is a lane item wearing a doc's clothes.
15. **The box partition answers WHO, not WHAT, and using it to pick lane CONTENT drags the
    whole backlog under one umbrella.** "Every GPU-free item is A's" was written to say which
    box may take an item and was read as which items belong to this plan, so a lane of six
    arrived holding a printer margin, three ANSI operator families and a `cons` set-operation
    row -- all real work, none of it about running a checkpoint, and one of them (`597`, the
    four `geom:` MODEL readers) pulled in by a NAME COLLISION with the subject. The
    correction is two questions asked separately: **the umbrella's subject selects the item,
    the box constraint selects the taker**, and an item that passes only the second belongs
    to its own track. `.kb`-style test: name the item's connection to the umbrella's goal in
    one clause without using the word "and".
16. **A bisect must hold the TREE constant and vary only the commit.** Chasing `748`'s red,
    four probes ran in four fresh worktrees and produced a clean, wrong answer -- green,
    green, green, red at the lane's last commit: an ordinary-looking bisect naming a culprit
    that was innocent. Every green probe had also changed the working DIRECTORY, and the
    working directory was the variable. The run that settles it stays in the checkout that
    is red and moves one file: `git checkout <older> -- <the suspect>`, re-run, still red.
    **A probe that moves the box and the commit together measures their sum**, and a bisect
    is the shape of experiment most likely to hide that, because its output looks like a
    verdict either way.

## What is deliberately not in the plan

- **Not an inference framework.** The forward pass stays one Lisp file; the layer became a
  KIND with options (`676`) and two more kinds joined it (`677`, `678`) only because the
  newest small models are hybrids. Gemma 4 waits until asked for.
- **Not mixed-precision training.** `torch:` stays f32/f64; bf16 is a storage width for
  weights, and nothing here changes what an activation is.
- **Not the device, beyond the GEMV.** `--gpu` takes `vec:matvec` over bf16 and Q8_0 weights
  and declines every other type; declining correctly is what `.todo/483`'s exhaustive
  switches buy.
