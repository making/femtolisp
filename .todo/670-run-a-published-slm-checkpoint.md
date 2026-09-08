# 670. Run a published SLM checkpoint: what Hugging Face ships, loaded as shipped

Difficulty: High (the umbrella; the children are sized individually)

Filed 2026-09-03 from the re-verification of `.todo/482` (`bfloat16`), whose README "Round
2" is the measurement record; `.todo/482` stays the width half of this.

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
| **bf16** | THE width. 1.5-2.1x f32 on one thread (Graal / C2), 1.6x on 20; widening exact; every checkpoint is in it | `.todo/482` (483-490) |
| **IEEE f16** | not a width -- a **load-time conversion** into `#f` / `#bf16`. A fused f16 GEMV is 0.30-0.58x on either JIT | `.todo/671` |
| **Q8_0** (32 int8 + a scale) | a **read-only weight matrix** type with an integer-dot GEMV: 1.4-1.6x f32 on one thread under Graal, 1.7-1.9x under C2, 2.2-3.3x on 20, a quarter of f32's bytes. On the device since `728`: the kernel is the CPU contract's BITS, 5.3 ms of GEMV a forward against bf16's 7.7, the forward 13.9 against 16.7 (1.20x), tokens byte-identical with the flag on and off | CPU: `672`, `706`. Device: `728`. The row slice without a scratch file: `.todo/732` |
| **Q4_0 / Q4_K** | not a CPU item: the nibble unpack is ALU-bound at 5.7 GB/s (1.1x f32 for 8.5% error). **Refused on the device too, twice** -- `718` on a decode profile, then `726` at the forward's own shapes. Behind `728`'s Q8_0 its increment is bounded by the byte ratio, ~2.7 ms of a 13.9 ms forward, at 8.5% error against 0.75%, so the refusal's reason is now ORDER, not size | `.kb/gpu.md`, "No Q4_0 / Q4_K weight width"; kernel rows in `.todo/artefacts/123-gpu-acceleration/README.md` |

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
BF16 checkpoint loaded into `#f` BEFORE the bf16 width existed, and both readers were
debugged at f32 with the kernels out of the picture.

## What runs today

**A published checkpoint runs, in three formats, no Python and no conversion step.**
Qwen3.5-0.8B from its BF16 safetensors AND from ggml-org's BF16 GGUF, **token for token
identical between the two**; TinyLlama-1.1B-Chat from safetensors and an F16 GGUF, same
forty tokens; stories15M converted to GGUF answers with `run.c`'s own text token for token
-- the one EXTERNAL oracle, and the one that caught a live bug. **On all four backends
since `693`.**

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
The count walked 232 -> 234 -> 235 -> 237 on dorian while reading 237 on GB10 and the
arithmetic never closed to the unit. It does not have to: **the two boxes' report-class
lists are identical, name for name**, so every class is present on both and what differs is
only what each SKIPS. A later run therefore diffs against a list known to be SHARED, and a
name that leaves is attributable to the box or to the change, never to the boxes having
always differed. That list held across a lane that changed the classes it names -- the
first evidence the discipline has produced rather than assumed. Each certification writes
its own list beside the others in
`.todo/artefacts/670-run-a-published-slm-checkpoint/`.

- **dorian certifies `20d8ac979`** at the close of A's six-item lane: 10159 / 0 / 0 / 290
  skipped, **238** reports, exit 0. **The set gained exactly one name against BOTH earlier
  lists** -- `am.ik.rontolisp.ansi.AnsiChapterRunnerTest`, the class `681` added -- which is
  the outcome the discipline is for: a diff of one, attributable to a named change. The skip
  count moved 283 -> 290 and is left UNATTRIBUTED, as its predecessor was.
- **GB10 certifies `b6d0ea513`** at the close of B's two-item lane: 10128 / 0 / 0 / 189
  skipped, 237 reports, exit 0, `GpuTest` included (59 tests, 560.7 s).

The heads are a lane apart and are NOT one certification; jointly they establish that no box
is red, and dorian cannot verify B's `am.ik.gpu` / `eval/LinalgGpu*` / `codegen/jvm/JvmGpuTemplate`
drift at all. Both are taken by the ORCHESTRATOR on `develop`, never from a lane's worktree
(rule 4) -- a lane's combination exists nowhere else.

Three things a reader needs before comparing two runs:

1. **A total taken before `0e65326b` is not comparable to one taken after.**
   `LispFormatterTest` used to walk `Path.of(".")` and format every `.lisp` under
   `.claude/worktrees/`, so one term of the comparison was how many agents had run on that
   box recently.
2. **The cross-box difference is a SKIP difference, not a class difference**: dorian's 283
   against GB10's 189 is `.todo/708`'s 87, from seventeen classes present on both that skip
   different amounts. Contamination never reached skips.
3. **A skip count is only a signal against a prior count for the SAME slice.** Its designed
   meaning and its defect meaning are the same integer, and `Tests run` is invariant under
   skipping but not under deletion -- a skipped leg keeps the headline total while removing
   the coverage, which is how `682` was accepted by a run that skipped the part of the suite
   its rename was most likely to break.

The argument for taking the run from `develop` is that it is the run that found the red:
`LispFormatterTest`'s walk raced a scratch file another test writes into the project root,
giving `Tests run: 9425, Errors: 1` while six lanes' worktrees were green throughout. Twice,
coverage fell into the SEAM between two correct plans with nobody skipping an assigned step:
**a verification owed by one party and skipped by everyone else is a gap that looks exactly
like coverage until someone checks who actually ran it.**

## Findings, and where each one now lives

Pointers, not records -- the home is where it gets updated.

- **`483`'s rule is stated wrong in 483**: not "never write a `default`" but **"an arm
  matching two or more permits IS a default, whatever it is spelled"**. `.kb/vec.md`.
- **`%la-gather-strided` has five readers** and grepping the name finds two. `.todo/687`.
- **Seven sites hand-write the bf16 conversion arithmetic**, and only `am.ik.rontolisp.BFloat16`
  is the authority. `.todo/487`'s remainder.
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
-- the surface-accounting overhead in `.todo/709` exists entirely because two lanes on one
box can touch one mechanism without either seeing the other. **Documentation-only items are
the stated exception and run BESIDE the lane** -- rule 14.

Model by difficulty, `effort=high` throughout: **High -> Fable, Medium -> Opus, Low ->
Sonnet.** A dead worker is RESUMED, never respawned. An item the lane's work turns up is
FILED and left for the next lane's planning, never worked recursively.

**The pool is split by the one thing the boxes do not share.** Every item that needs the
DEVICE is B's; **every GPU-free item is A's.**

### Orchestrator A -- dorian, GPU-free, the model side

A's previous lane closed `722`, `724`, `701`, `680`, `681` and re-read `715`, and ran
`733`, `734`, `738` and `695` beside it. Its subject was **the two instruments the
checkpoint path is measured with, then the error surface three items describe from three
sides** -- and in four of the six the thing measured was the instrument, not the subject.

- `722` -- **the trap was not ours.** `cranelift-frontend`'s SSA builder leaves a block
  parameter on a `try_table` catch when the protected body holds a loop with an inner
  two-predecessor merge and a call after it; each `try_call` then passes the local as an
  exceptional-edge ARGUMENT, spilled to a slot the stack map does not list, so a copying
  collection during the call moves the object and updates only stack-mapped slots. The
  "one-line source edit moves it" behaviour was pregrow -- 16x emitted code bytes --
  shifting GC timing. **A cliff a source edit moves is a timing signature, not a codegen
  signature.** Unfixed through 49.0.0-rc.1; the workaround is `WasmLandingPad` at zero
  runtime cost, the mechanics and the reproducer are `.kb/wasm-landing-pad-refresh.md`, and
  the report is `.todo/731`.
- `724` -- the clock started at the end of the first loop iteration, so prompt forwards were
  inside the clock and outside the count. **A rate whose numerator and denominator are
  collected at different points is wrong by a factor that differs per arm**, which is why
  every row on the page compared with the others and with nothing else.
- `701` -- **the oracle's invocation was the divergence.** `llama-cli --reasoning-budget 0`
  does not set the template's `enable_thinking`; `--reasoning off` does, and then the
  prompts match byte for byte including the empty `<think>`. The genuine divergence was
  elsewhere: SmolLM2's own template injects a system turn a generic ChatML rendering does
  not, now `*chatml-smollm2*`.
- `680` -- **making the expander SIGNAL is only half the fix**, because macroexpansion runs
  outside the enclosing `handler-case`'s dynamic extent. Returning `(%program-error ...)`
  for the compilers to lower is what moves the signal to call time, on all four backends.
- `681` -- the report counted a lost form into the reason table and into NONE of
  `pass`/`fail`/`error`. **A denominator that drops what it cannot classify makes every fix
  look smaller than it is**; `680` landing first is what made it visible (840 -> 588 lost,
  denominator +252, numerator unchanged).
- `715` -- the ranking's own instrument mixed units: per-test `ERROR` rows and per-form
  `%%%EVAL` rows in one table, and an aux file loads into all 25 chapters, so one failed aux
  form reads as 25. Two owner columns pointed at items closed 2026-08-15 with the work
  undone -- **a closed owner is worse than no owner, because the row reads as taken.** Kept
  open deliberately: it is a standing READING, re-taken at the close of the lane that spends
  it.

**The current lane spends the ranking that re-reading produced.** The first item is the only
one BOTH instruments name; the next two are `680`'s own residue and decay if left, because
they are legible only while its mechanism is fresh; the last three are the ranking in payoff
order.

| # | item | difficulty | why here, why now |
| --- | --- | --- | --- |
| A-1 | `041` readtable and printing control -- the right-margin half | High | FIRST by the standing axis: an item BOTH instruments name is taken before one either names alone, and after `715`'s re-read this is the only one left. The ANSI `printer` chapter sits at 40.7%, and the right margin is the sole live gap remaining in the _Practical Common Lisp_ corpus -- four systems differ from SBCL by it and by nothing else. Scope it to the margin; `041`'s stream column is a separate half and does not come with it |
| A-2 | `736` the `substitute` / `remove` family rejects `:count` `:start` `:end` `:from-end` | Medium | Second, and the largest operator row the ranking has: 578 tests. It exists as a filed item only because `680` made the refusal CATCHABLE -- before that these were raw throws counted under one message, and what they report now is a real gap rather than a report artefact |
| A-3 | `735` a wrong-arity `funcall` is not signalled on the compiled backends | Medium | Third: `680`'s other residue, and the one that outranks its test count. The interpreter signals, the JVM answers NIL, wasm traps -- **three answers to one call**, which is a cross-backend divergence and not a missing feature. It is the half `680` could not reach because the check has no expansion-time site |
| A-4 | `732` a row slice of a quantized matrix without a scratch file | Medium | Fourth, and the lane's only checkpoint-path item. Filed by B's `728`, and GPU-free, so the partition makes it A's. `examples/llm` splits Qwen3.5's `attn_q` through a byte copy in `$TMPDIR` because a `quantized-matrix` is immutable and `file-position` does not seek -- so the program's correctness depends on a writable temp directory on four backends |
| A-5 | `740` the cons set and tree operator family is unowned | Medium | Fifth: 458 tests, and it is the row `715` found pointing at a CLOSED owner. Cheaper than it reads -- CLHS permits the `n*` forms to be aliases of their non-destructive twins, so the family is smaller than its name count |
| A-6 | `742` the standard limit and `boole-` constants are unbound | Low | Last because it is cheap and because of where it sits: `array-rank-limit` is one of the five links in the `universe.lsp` cascade that costs 504 tests, so a Low item feeds a chapter-sized one. Taking it last also puts a small item after the lane's two mechanisms, where a re-read of `715` closes the lane |

**A's pool, not in the lane.** All GPU-free.

- `739` (Low) -- the ANSI report's three remaining accounting defects plus the unit-mixing
  `715` found. **Instrument work, and it gates a price**: `741`'s 370 tests are held under
  reservation until the `in-package` skip stops limiting the `packages` chapter.
- `741` (High) -- the runtime package API, unowned for the same reason `740` was.
- `744` (Medium, 156 tests, 104 of them `arrays` alone) and `743` (Low, 51 tests behind one
  predicate) -- the next two ranking rows after the lane's.
- `679` (High) -- `'pi` reads as a double. **Its own headline was wrong and is corrected in
  place**: 715 traced the `universe.lsp` cascade form by form and `679` is one of five
  links, worth ~95 tests alone rather than the 618 it claimed.
- `731` (Medium) -- half of it is a person's action: filing the wasmtime bug is an external
  submission. The toolchain half (move off 47.0.3, re-verify every wasm leg) is takeable by
  any lane; the narrowing question is answered by the PIN, never by the version.
- `684` (Low) and `696` (Medium) each hold an **x64 half that is A's**, every `.todo/488`
  number behind them being aarch64.
- `721` (Medium) wants a quiet box and does not decay. `597` (Medium, the other four `geom:`
  model readers have no interpreter native), `689` (Medium, `jvm-export` handles do not
  carry bfloat16), `699` (Low, one UTF-8 lead-byte table and two hand-written copies),
  `737` (Low, pin that `.kb/README.md` lists every topic file exactly once).

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
| B-1 | `729` the binary's downcalls through SubstrateVM's own AOT route | High | The lane, and open-ended: `727` left the measurement done and the COST accepted rather than the design. 10.7 ns against 2-7 us on the same address in the same image, ~5 ms of a decode forward's ~1300 driver calls, and a `--blas` floor that exists only to pay for it. What is unsettled is the SHAPE -- a `-Pnative` source set substituting the binding halves of `am.ik.gpu.CudaDriver`, `eval/LinalgBlasKernels` and `am.ik.objc`, one interface method per shape across 45 CUDA + 6 BLAS + the objc table, against core libraries that import nothing. **The honest first step is deciding whether that seam is payable, and "not worth it" is a close** |

**The device pool did not refill this time.** Two lanes ago both closers filed a device
successor and the partition held on that; `728` filed `732`, which is GPU-FREE and went to
A. So B's lane is one item, and the aarch64/x64 axis that `684` and `696` hold is the next
thing to settle rather than to wait on.

**Not either lane's.** `730` (report the SVM findings upstream) is finished as writing and
needs a person to post it. `514` (`LinalgGpuTest` never finishes on Apple silicon) and `516`
(a variadic `objc:send` selector crashes the process) both want a **macOS box**, which
neither dorian nor GB10 is -- parking those is not a deferral under rule 3, since no box in
this plan can even fail them.

**The one decision still open, and not either lane's to take alone: `.todo/709` is an
explicit DRAFT and needs co-signing or cutting by both orchestrators.** It is process, so
one side adopting it unilaterally is the failure it is written about. It has now outlived
six full lanes, which is evidence about the item rather than about its subject. It is also
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
   without an owner. Two of nine were truly blocked.
4. **One session runs the full suite on `develop`, the other runs the GPU legs.** Three reds
   were invisible from every lane's own worktree.
5. **Never two device-touching runs at once, separately from who owns what.** `./mvnw test`
   includes `GpuTest`, so a full suite IS device-touching. **Ownership says who takes a
   result; exclusion says what may run at once** -- fusing the two produced a
   self-contradictory instruction to one lane.
6. **A suite can hold a defect invisibly while every case sits on one side of its condition,
   and the half that looks more exhaustive is the half that hides it.** Three in one day,
   including `692` against a `671` that closed claiming all four backends while its tests
   counted backends and never `--simd` on each (`694`). **The instrument is not exempt**:
   the corpus's own `vec:` cases were all under the `--simd` length gates until `705`.
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

## What is deliberately not in the plan

- **Not an inference framework.** The forward pass stays one Lisp file; the layer became a
  KIND with options (`676`) and two more kinds joined it (`677`, `678`) only because the
  newest small models are hybrids. Gemma 4 waits until asked for.
- **Not mixed-precision training.** `torch:` stays f32/f64; bf16 is a storage width for
  weights, and nothing here changes what an activation is.
- **Not the device, beyond the GEMV.** `--gpu` takes `vec:matvec` over bf16 and Q8_0 weights
  and declines every other type; declining correctly is what `.todo/483`'s exhaustive
  switches buy.
