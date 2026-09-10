# Lanes and certification: running two orchestrators on two boxes

**Invariant: a lane is one worker per orchestrator, serialized; a certification is a full
`./mvnw test` taken by the ORCHESTRATOR on `develop`, and what it certifies is failures,
errors and the report-file SET -- never the totals.** The sixteen standing rules at the end
are cited BY NUMBER from `.todo/` items and the numbering is fixed.

Earned by the `.todo/670` umbrella (a published SLM checkpoint runs from the file someone
downloaded; filed 2026-09-03, closed 2026-09-10), whose two lanes ran on the two boxes
below for a week. The plan died with the umbrella; this is what outlived it. The
measurement rules the lanes leaned on are `.kb/measurement-probes.md`; how the suite
sequences is `.kb/test-execution.md`; what a rename breaks is `.kb/directory-rename.md`.

## Lanes: ONE worker per orchestrator

- **Each orchestrator drives ONE lane at a time, serialized**: an item completes, is
  committed and pushed, and only then does the next start. Two lanes on one box can touch
  one mechanism without either seeing the other; serialization is what removes that
  accounting. **Documentation-only items are the stated exception and run BESIDE the
  lane** (rule 14).
- Model by difficulty, `effort=high` throughout: **High -> Fable, Medium -> Opus, Low ->
  Sonnet.** A dead worker is RESUMED, never respawned. An item the lane's work turns up is
  FILED and left for the next lane's planning, never worked recursively.
- **Two questions, and only one of them is the box.** WHO may take an item is split by the
  one thing the boxes do not share: an item that needs the DEVICE is the GB10 lane's, a
  GPU-free one can be dorian's. WHAT belongs in a lane is the lane's SUBJECT, a different
  question (rule 15).
- **Parking is not deferral.** An item that no box in the plan can even fail (a macOS-only
  reproduction, on two Linux boxes) is parked, not deferred under rule 3; an item finished
  as writing and waiting on a person's action (an upstream report) is not in the
  engineering queue at all.

## The two machines

- **`dorian`** -- Xeon E5-2697A v4, Broadwell x86-64, 64 threads, 251 GB, GraalVM 25.0.4,
  AVX2 256-bit, **no avx512**. Orchestrator A's box; no GPU.
- **GB10** -- aarch64 Cortex-X925, 20 cores, 121 GB, NEON 128-bit, CUDA. Orchestrator B's
  box, and the only one that can run the GPU legs.

A measurement without its base commit, JIT, machine and load average is not comparable to
another; a quiet window is per-box and each side takes its own. A box's checkpoint
inventory is the box's, read with `ls` and never from a file here (rule 9). What IS
durable is the published file's size and digest (rule 10); the three the lanes ran most:

| file | bytes | sha256 |
| --- | --- | --- |
| Qwen/Qwen3.5-0.8B `model.safetensors-00001-of-00001.safetensors` | 1746942600 | `04b1c301231dd422b8860db31311ab2721511346a32cb1e079c4c4e5f1fe4696` |
| ggml-org `Qwen3.5-0.8B-BF16.gguf` | 1557662496 | `9a7bed4041b7975e0f71fa34670d1e9025213bc92905ac0db75d36c4fa3fa623` |
| ggml-org `Qwen3.5-0.8B-Q8_0.gguf` | 833592096 | `37ae482d336108d23516fa35e8e0c4126688d81018b87178a18d752a1357814f` |

None of it belongs in the repo; `examples/llm/.gitignore` keeps the `stories15M` artefacts
out (rule 12).

## Certification

**What a run certifies is failures, errors and the report-file SET -- never the totals.**
The count walked 232 -> 238 on dorian while reading 237 on GB10 and the arithmetic never
closed to the unit. It does not have to: **the two boxes' report-class lists are identical,
name for name**, so every class is present on both and what differs is only what each
SKIPS. A later run therefore diffs against a list known to be SHARED, and a name that
leaves is attributable to the box or to the change, never to the boxes having always
differed. Each certification writes its own list beside the others in
`.todo/artefacts/670-run-a-published-slm-checkpoint/` (`report-classes-<box>-<commit>.txt`,
the sorted basenames of `target/surefire-reports/*.txt`), and that README holds the runs.

- **Taken by the ORCHESTRATOR on `develop`, never from a lane's worktree** (rule 4): a
  lane's combination exists nowhere else, and rule 16 is why a worktree is not a
  substitute even when it is convenient. The run from `develop` is the run that FINDS the
  red -- twice the red was visible nowhere else: `LispFormatterTest`'s walk raced a scratch
  file another test writes into the project root while six lanes' worktrees were green
  throughout, and `.todo/748` was red in the checkout that carries worktrees and green
  inside every one of them.
- **dorian cannot verify the device side at all** (`am.ik.gpu`, `eval/LinalgGpu*`,
  `codegen/jvm/JvmGpuTemplate`); the two boxes' heads are lanes apart and are NOT one
  certification. Jointly they establish that no box is red for a reason in the tree.
- A run that does NOT certify is still recorded with its list: a set unchanged name for
  name under a single error in a class present on both boxes cannot be a dropped class, a
  renamed one or a skipped leg, which leaves the box itself (`.todo/748` / `.todo/749`).

Three things a reader needs before comparing two runs:

1. **A total taken before `0e65326b` is not comparable to one taken after.**
   `LispFormatterTest` used to walk `Path.of(".")` and format every `.lisp` under
   `.claude/worktrees/`, so one term of the comparison was how many agents had run on that
   box recently. `.todo/748` was that defect re-introduced in Lisp, inside the corpus.
2. **The cross-box difference is a SKIP difference, not a class difference**: dorian's 290
   against GB10's 189 is `.todo/708`'s 87, from seventeen classes present on both that skip
   different amounts.
3. **A skip count is only a signal against a prior count for the SAME slice.** Its designed
   meaning and its defect meaning are the same integer, and `Tests run` is invariant under
   skipping but not under deletion -- a skipped leg keeps the headline total while removing
   the coverage, which is how `.todo/682` was accepted by a run that skipped the part of the
   suite its rename was most likely to break (`.kb/directory-rename.md`).

Twice, coverage fell into the SEAM between two correct plans with nobody skipping an
assigned step: **a verification owed by one party and skipped by everyone else is a gap
that looks exactly like coverage until someone checks who actually ran it.**

## Standing rules, in the order they cost the most

Cited by number from other items -- **the numbering is fixed.** Rule 7 applies to this
file: a rule one lane derives is said to the other lane before it is written here.

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
   including `.todo/692` against a `.todo/671` that closed claiming all four backends while
   its tests counted backends and never `--simd` on each (`.todo/694`). **The instrument is
   not exempt**: the corpus's own `vec:` cases were all under the `--simd` length gates until
   `.todo/705`, and every `append` it runs was under the recursion depth until the box grew
   (`.todo/748`, `.todo/749`).
7. **A rule one lane derives from one measurement is a hypothesis until the other lane has
   tried to break it.** Three corrections in one day, each of which would otherwise have
   entered `.kb` as a law. What survives from the first: a failure count's SIZE narrows the
   SEARCH, never the VERDICT (`.kb/measurement-probes.md`). **Say it to the other lane
   before writing it into `.kb`.**
8. **A run certifies a head it did not run against when the FILE SET says so, never the
   elapsed time.** `git diff --stat <ran-at> <head> -- src/` empty means a re-run would only
   re-measure `.todo/` edits. One command, and it is the whole argument.
9. **An umbrella's status paragraph is evidence only where no child covers the same fact.**
   Where a child does, the child wins and the umbrella POINTS. The umbrella once said "the
   checkpoints are gone" while `.todo/677` carried the correct paths, and two lanes were
   sent to re-download 12 GB that was on disk. **A restated fact also decays PER BOX**, and
   that direction is worse: it skips a needed re-fetch rather than repeating an unneeded
   one.
10. **Record a checkpoint's SIZE and sha256 beside its path**, because provenance is
    recoverable from the file but not the file from the provenance: Hugging Face answers
    `/api/models/<id>?blobs=true` with the LFS sha256, so a checkpoint whose repo path was
    lost is re-identified by matching bytes already held. **The digest and the refusal to
    guess are two independent goods.** State the mechanical half first: a written repo id
    reads as known, so nobody queries the manifest.
11. **A closer must check for items waiting on an EVENT, not only for items naming its
    number, and no grep finds those.** `.todo/682` was gated on "the first published
    checkpoint that runs end to end"; it fired THREE times unnoticed. What works: when a
    Done section describes a capability arriving for the first time, **grep `.todo/` for
    the CAPABILITY** -- the format, the model class, the surface -- not the number.
12. **A directory-local ignore rule protects by LOCATION, so moving the rule stops
    protecting whatever stayed** -- and what stayed is invisible to the rename precisely
    because being ignored is what kept it out of it. `.todo/682` moved
    `examples/llama2/.gitignore` correctly and the next `git add` swept 61 MB onto develop.
    The check is one command at the one moment the files are visible: **after moving a
    directory that contains a `.gitignore`, run `git status --porcelain -uall` for
    untracked files at the OLD path before the next `git add`**. The card is
    `.kb/directory-rename.md`.
13. **A pin counts only in the lane that RUNS it, so "no test asserts X" is a claim about
    the lane and not about the assertion.** `.todo/705` was filed as "no suite asserts on
    decoded text" while `ExamplesE2eTest` had matched forty tokens against run.c's own
    output on four backends since before the filing -- in a job `./mvnw test` skips.
    **Name the job an assertion runs in before concluding there is none**, and when the
    answer is "a job the change does not run", the work is to move the pin, not to write
    it again.
14. **A documentation-only item does not contend for the lane's serialization.** Four
    closed beside the lane with no interaction (`.todo/733`, `.todo/734`, `.todo/738`,
    `.todo/695`), because serialization exists to stop two workers touching one MECHANISM
    and a doc-only item touches none. The one near-collision was `.kb/error-handling.md`,
    wanted by `.todo/695`'s identifier audit and by `.todo/680`'s new section at the same
    time, and it was avoided by naming that file to the doc worker as off-limits. **So the
    exclusion is per-FILE and has to be stated when the lane is designed, not discovered
    when it bites** -- and "documentation-only" means it, since an item that must MEASURE
    to write the doc is a lane item wearing a doc's clothes. A `.kb` file named by both
    lanes at once (`.kb/bfloat16.md`, 2026-09-10) is resolved by rule 7: the lane that is
    IN the file takes the edit, the other says the correction to it instead of writing it.
15. **The box partition answers WHO, not WHAT, and using it to pick lane CONTENT drags the
    whole backlog under one umbrella.** "Every GPU-free item is A's" was written to say
    which box may take an item and was read as which items belong to this plan, so a lane
    of six arrived holding a printer margin, three ANSI operator families and a `cons`
    set-operation row -- all real work, none of it about running a checkpoint, and one of
    them (`.todo/597`, the four `geom:` MODEL readers) pulled in by a NAME COLLISION with
    the subject. The correction is two questions asked separately: **the umbrella's
    subject selects the item, the box constraint selects the taker**, and an item that
    passes only the second belongs to its own track. Test: name the item's connection to
    the umbrella's goal in one clause without using the word "and".
16. **A bisect must hold the TREE constant and vary only the commit.** Chasing
    `.todo/748`'s red, four probes ran in four fresh worktrees and produced a clean, wrong
    answer -- green, green, green, red at the lane's last commit: an ordinary-looking
    bisect naming a culprit that was innocent. Every green probe had also changed the
    working DIRECTORY, and the working directory was the variable. The run that settles it
    stays in the checkout that is red and moves one file: `git checkout <older> -- <the
    suspect>`, re-run, still red. **A probe that moves the box and the commit together
    measures their sum**, and a bisect is the shape of experiment most likely to hide
    that, because its output looks like a verdict either way.
