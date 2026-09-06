# 718. Q4_0 / Q4_K on the device: the width the plan named and no item ever owned

Difficulty: High

Filed 2026-09-06 by orchestrator B, at the close of `.todo/490`, from a POINTER rather than
from a measurement. Nothing here is new evidence; what is new is that the pointer stopped
resolving.

## The gap

`.todo/670`'s width table has four rows. Three name an item that did the work. The fourth:

| **Q4_0 / Q4_K** | not a CPU item: the nibble unpack is ALU-bound at 5.7 GB/s (1.1x f32 for 8.5% error). A device width | `.todo/490` |

`.todo/490` was titled "bf16 on the device" and closed 09-06 having taken `vec:matvec` over
a **bfloat16** matrix onto CUDA. It never touched a nibble. So the row now cites a CLOSED
item for a width that item did not implement, which is the exact failure standing rule 2
names -- **a stale pointer fakes a finish** -- and it is worse than an unowned row, because
a closed citation reads as done to anyone who does not open the file.

The measurement behind the row stands and is not in question: `.todo/490`'s ancestor
measured the CPU unpack at 5.7 GB/s, 1.1x f32 for 8.5% error, which is why Q4 was ruled off
the CPU. "A device width" was the CONSEQUENCE drawn from that, and consequences need an
owner. This item is that owner.

## Why it is not simply "do Q4 on the device now"

Two results from 09-06 change the question that would have been asked in 09-03:

1. **The device arm does not currently win on this box.** `490` measured Qwen3.5-0.8B at
   11.3 tok/s under `--gpu --simd --parallel` against **18.5** under `--simd --parallel`.
   Once the GEMV leaves the critical path the bottleneck is elsewhere, so a NARROWER device
   GEMV buys less than the width table implies -- it makes a step faster that is not the
   step being waited on.
2. **`490`'s accumulator finding is the transferable part.** The double FMA was a compute
   ceiling on this card (~70 G/s, one FMA per element), and moving `#f`/`#bf16` to a
   compensated float pair is what unblocked it. A Q4 kernel does MORE arithmetic per byte
   than a bf16 one, so it lands on the same ceiling harder, and any Q4 estimate taken
   before reading `.kb/gpu.md`'s precision row will be wrong in the optimistic direction.

So the first question is not "how fast is a Q4 device GEMV" but **"what does this box wait
on when the GEMV is already off the critical path"** -- and that is answerable without
writing a nibble kernel at all.

## Done

1. **The row resolves.** `.todo/670`'s Q4 row cites this item, not a closed one.
2. **Establish what the device arm actually waits on** at bf16, before any Q4 work: profile
   one decode step of Qwen3.5-0.8B under `--gpu --simd --parallel` on GB10 and say where
   the 86 ms goes, given the GEMV is ~7 ms of it. `.todo/496`'s method (bucket kernel time
   by grid shape, `nsys stats` inside the container) is the one to reuse.
3. **Then decide, and record the decision either way**: a Q4 device GEMV, or a written
   refusal saying which measurement retires the width. **A refusal is a valid outcome and
   must be recorded as one** -- an unwritten "we decided not to" is how this row came to
   point at 490.
4. If it is built: `am.ik.gpu` gains no class without a `JvmGpuRuntimeBuilder.GPU_CLASSES`
   entry, `--gpu` declines every other new type by `.todo/483`'s exhaustive switch, and
   Metal declines by capability the way `supportsBfloat16()` does.

## Not in scope

Q4 on the CPU. It was measured out and stays out; re-measure only on the trigger `.todo/670`
already states (the Vector API growing a dot-product or a narrower conversion).

## Reading

- `.todo/670`, the width table and standing rules 2 and 9.
- `.todo/artefacts/123-gpu-acceleration/README.md` and `.kb/gpu.md`, "The GEMV, and the
  matrix that stays" -- the accumulator precision row `490` added.
- `examples/llm/README.md`, "bf16 weights on the device" -- the per-flag `tok/s` table the
  11.3-against-18.5 comparison comes from.
- `.todo/716` (the residency cliff) and `.todo/717`, both filed by `490`'s lane.
