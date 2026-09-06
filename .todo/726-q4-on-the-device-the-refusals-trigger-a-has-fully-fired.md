# 726. Q4 on the device: the refusal's trigger (a) has fully fired

Difficulty: High

Filed 2026-09-06 by `.todo/725`'s closing profile (GB10, Qwen3.5-0.8B from the BF16 GGUF at
`-w bf16`, JVM class output). The refusal itself and its arithmetic live in `.kb/gpu.md`,
"What is deliberately NOT here" -- go there, not here, for what the width would cost.

## What fired

`.todo/718` refused a Q4_0 / Q4_K weight width ON THE DEVICE and wrote down the two
conditions under which the refusal is to be re-measured. Condition (a) was "`.todo/723` and
`.todo/725` bring the host floor down". Both have now closed, and the arm they were measured
against is a different machine:

| `--gpu --simd`, ms a forward | bf16 GEMV of it | Q4 ceiling | against `--simd --parallel` |
| --- | --- | --- | --- |
| 45-51 (`718`, the refusal) | 6.8 ms, 15% | under 10% | trails 1.9x |
| 25.0 (after `723`) | 6.8 ms, 27% | ~18% | edges past |
| **18.5 (after `725`)** | **6.73 ms, 36%** | **~26%** -- 4.8 ms of 18.5 | **leads** (21.6) |

The kernel time did not move; the arm around it shrank 2.6x. **A refusal computed as a share
decays when the denominator does**, which is the whole reason `718` wrote the trigger down
rather than closing the question.

## Do

Re-take the refusal, not the width. The output is a decision with its arithmetic, and a
"still refused" is as good a close as a build -- `718`'s was.

- Re-measure the ceiling on the CURRENT arm rather than scaling this table: a Q4_0 matrix is
  0.28 of a bf16 matrix's bytes, but the bf16 kernel is only bandwidth-bound above ~6144x1024
  (`.kb/gpu.md`, "The GEMV, and the matrix that stays"), and this model's per-layer matrices
  are smaller than the head. A width that saves nothing on the shapes that are NOT at the
  bandwidth saves less than 0.72 of 6.73 ms.
- Weigh it against the cost list `.kb/gpu.md` already carries -- a fifth packed type on every
  backend with its scalar oracle, the Q4_0 block AND K-quant super-block readers (a Q4_K_M
  file also carries Q5_K and Q6_K), the CPU fallback the flag needs since `--gpu` may not turn
  an answer into an error, and `.todo/483`'s exhaustive switches.
- If the answer is still no, say so IN `.kb/gpu.md` with the new numbers and a new trigger.

## Done

- `.kb/gpu.md`'s "No Q4_0 / Q4_K weight width" bullet carries the arithmetic of the day it was
  re-taken, and either a shipped width or a trigger that has NOT fired.
- If a width ships: `.kb/quantized-matrix.md`, `.todo/483`'s switches, the GGUF reader, the CPU
  decline arm, and the `examples/llm` rungs in `examples/llm/README.md`.

## Not in scope

The CPU half (measured out in `.todo/670`'s width table, and unchanged), and trigger (b) --
a discrete card with its own memory, which no calibration box has.
