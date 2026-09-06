# 724. `llm.lisp`'s `achieved tok/s` is sampled tokens over the prompt's clock, plus the JIT warm-up

Difficulty: Medium (the fix is Low; the blast radius is every `tok/s` row in `examples/llm/README.md`)

Filed 2026-09-06 by `.todo/718`, which set out to explain "an 86 ms token" and found a
56 ms forward under the profiler and 45 without it.

## What the harness does

`generate` starts its clock at the end of the FIRST loop iteration whatever that iteration
was -- `(unless start (setq start (get-internal-real-time)))` runs for a prompt position
too -- and divides `generated`, which counts only the positions the model SAMPLED, by the
time since. run.c has the same clock and divides `pos - 1`, every position after the first,
so its rate is consistent with itself; ours has run.c's denominator under a smaller
numerator. With `-m chat` on Qwen3.5 the rendered prompt is 21 ids, so `-n 64` prints
64 tokens over 84 forward passes: **0.76 of the forward rate before anything else**. And
the first ten or so forwards run 1.5-2x slow while the JIT warms (the profiler's head-to-head
periods: 93 86 78 77 93 77 81 77 79 74 62 57 58 ... ms), which the 84-forward average carries
and a longer run dilutes.

Measured on the GB10 (quiet, `-w bf16`, the BF16 GGUF, JVM class output, medians of two):

| arm | printed at `-n 64` | steady ms/forward (`-n 128` minus `-n 64`, over 64) | forwards/s |
| --- | --- | --- | --- |
| `--simd --parallel`, 16 threads | 19.3 | 24 | 41 |
| `--gpu --simd --parallel`, 16 | 11.0 | 46 | 22 |
| `--simd`, one thread | 7.6 | 81 | 12 |
| `--gpu --simd`, one thread | 10.8 | 45 | 22 |

So the printed figure is 1.7-2.1x below the rate the program actually decodes at once warm,
and the gap is not constant across arms (the device arm's first forward also pays the
context creation and the 1.5 GB upload). The rows on the README page compare with EACH
OTHER -- every one was printed the same way, on prompts of the same length within a table
-- and with nothing else: not with `llama.cpp`, not with a forward rate, and not across
tables whose prompts differ (TinyLlama's raw "Once upon a time" is 5 ids against the chat
prompt's 21, so its rows sit 0.94 of their forward rate where the Qwen rows sit 0.76).

## Do

1. Start the clock at the first SAMPLED position, and count from there -- run.c's shape
   with run.c's consistency. Or keep the clock and count what it counts. Either way the
   printed rate is a rate of the thing counted.
2. Consider printing the steady rate beside it (the last half of the run, or "ms per
   forward after the first N"), which is what every comparison on the page wants; the
   two-length difference above is the manual version.
3. Then the README: either re-measure the per-flag and per-thread tables under the fixed
   harness, or state the bias at the top of "A Hugging Face checkpoint" once and leave the
   numbers, dated. `.todo/489`'s and `490`'s closing accounts in `.todo/history/2026-09.md`
   cite the printed figures; a note there is not needed, the row points at the README.
4. `examples/examples.yaml`'s `equals` stories do not read the rate; `ExamplesE2eTest`
   `-Drontolisp.examples.only=llm/` is the check that the loop still prints the same text.

## Not in scope

The forward rate itself (`.todo/723`, `.todo/725`).
