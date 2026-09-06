# 716. A model over the residency budget decodes BELOW `--simd`, and nothing says so

Difficulty: Medium

Filed 2026-09-06 from `.todo/490` step 6 ("re-upload every token is the failure mode and it
must be legible rather than merely slow"). The measurement is in
`examples/llm/README.md`, "bf16 weights on the device", and the probe is
`.todo/artefacts/123-gpu-acceleration/ResidencyCliff.java`.

**What was measured.** Qwen3.5-0.8B at bf16 (1.5 GB of weights) on the GB10, JVM class
output, `--gpu --simd`: 10.8-11.5 tok/s with the derived budget; with the budget forced
to 512 MB (above the largest matrix, below the model) **6.7-6.8 tok/s -- below `--simd`
alone at 7.7**; 256 MB and 64 MB, 5.8-5.9. The mechanism: an evicted matrix is a first
sight again on its next token (declined, the CPU runs it and the library MARKS it) and an
upload on the one after, so the loop alternates between the CPU rate and a cold trip, and
the flag makes the program slower than not having it. The 64 tokens stayed byte-identical.
The probe's own counters at 512 MB: 510 MB resident at the end, 3440 residency hits
against 14381 misses over the run.

**Under the interceptors this needs a model larger than the card.** Both interceptors run
lazy results, whose budget is the headroom rule (`.kb/gpu.md`: everything the device has
less an eighth, never below 512 MB), so the eager `min(free / 4, 1 GB)` cap never applies
to a `--gpu` program; on this box the cliff is at ~100 GB of weights. On an 8 GB or 16 GB
discrete card it is at ~7 or ~14 GB, which is a 4B-7B model at bf16 -- exactly what a
user with such a card will try.

## Do

- Make the cliff legible. The library counts everything needed (`residentBytes`, the
  cache's `hits`/`misses`, the evictions); what is missing is a surface. Candidates, one
  is enough: the `--gpu` startup line (`RontoLispCli.enableGpu`'s `warn`) could be
  matched by an exit line with resident bytes and eviction count; or `Gpu.description()`
  could carry the budget so the user can compare it with the model size. Do not add a
  knob before the surface exists.
- Consider the policy too: a matrix evicted for BUDGET and then offered again could be
  taken cold rather than declined -- the cold trip measured 10.9 ms against the CPU's
  22.6 for the head here, on unified memory -- but that reverses the two-sight rule's
  premise, so measure it on a discrete card before touching it.

## Verify

- `ResidencyCliff.java` at a budget below the model prints the budget it ran under and
  the program's output names the shortfall, on the JVM class output and the interpreter.
