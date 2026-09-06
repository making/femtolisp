# 723. Under `--gpu` the DeltaNet host loops run 4x slower: a residency guard per typed store, and two loops that were never typed

Difficulty: Medium

Filed 2026-09-06 by `.todo/718`'s decode profile (GB10, Qwen3.5-0.8B from the BF16 GGUF,
`-w bf16`, JVM class output; the record is `.kb/gpu.md`, "The GEMV, and the matrix that
stays", and the probes `decode-per-token.py` / `decode-jfr-agg.py` in
`.todo/artefacts/123-gpu-acceleration/`).

## What was measured

A steady forward pass is 45 ms under `--gpu --simd` and 24 under `--simd --parallel`; the
device kernels are 7.5 ms of the 45 and the driver calls 11.9. **The rest is the host, and
the host is slower WITH the flag than without it**: the three Lisp functions of the Gated
DeltaNet mixer cost 6.8 ms a forward on the `--simd` build (one thread, JFR) and ~30 ms on
the `--gpu --simd` build --

| function | `--simd` | `--gpu --simd` | what the samples sit in |
| --- | --- | --- | --- |
| `gated-delta-rule` (`deltanet.lisp`) | 1.8 ms | 20 ms | `_gpuWritten` from the TYPED loop, once per store of the 128x128 state |
| `causal-conv` (`causal-conv.lisp`) | 4.3 ms | 8.5 ms | `_gpuMaterialize` in `_fvAref2` and `_gpuWritten` in `_fvAset1`: the loop is BOXED on both arms |
| `silu-in-place` (`deltanet.lisp`) | 0.7 ms | 2.2 ms | the same, through `_ivAset1`: boxed on both arms |

40% of the device arm's main-thread samples are in `RontoLispGpuCudaGemm.materialize` /
`Gpu.written` / `DeviceResidency.written` / `recentClaim` / `Lookup.hashCode`. That is 18-23 ms a
forward -- the whole margin by which the arm loses to `--simd --parallel`.

## The two mechanisms

1. **A typed loop calls `_gpuWritten` on EVERY store** (`.kb/jvm-typed-loops.md`: "under
   `--gpu` every typed store calls `_gpuWritten`, as `_fvAset*` does";
   `JvmTypedLoopCompiler.aset`). `CudaGemm.written` materializes first and then takes the
   residency lookup, so each store is two hash-map probes plus the ring walk -- for the
   rank-1 update `S[j][i] <- S[j][i] * decay + d * k[i]` that is 16 heads x 18 layers x 16384
   elements = 4.7 M guard calls a token. The loop already hoists `_gpuMaterialize` to loop
   ENTRY for every array (`hoistArrays`); `_gpuWritten` can be hoisted the same way for every
   array the body stores into -- the array is about to be written, so dropping its device
   copy before the first store instead of at each is the same contract, and a loop of zero
   trips drops a copy it did not need to (speed only; the next two sights re-upload it).
   The `.kb/jvm-typed-loops.md` sentence and the `.kb/gpu.md` writer enumeration change
   with it.
2. **`causal-conv`'s loops and `silu-in-place` are not typed on ANY arm** -- their samples
   are in the boxed `_fvAref2` / `_ubRead` / `_ivAset1` / `Long.valueOf` with or without the
   flag (86 + 29 + 21 samples of 3047 on the `--simd` arm). Both look like the subset:
   `silu-in-place`'s body is `(/ u (+ 1.0 (exp (- u))))` -- **unary `-`**, which the subset
   lists only as a binary operator; `causal-conv` has a `let`-bound `acc` assigned in a
   nested `dotimes`, a free `m` used both as a count and as an index, and `(- m 1)` as a
   count. Find the disqualifier with `-Drontolisp.debug.notypedloops=true` as the A/B and
   the pinning test's method, and either admit the form (unary minus is one line) or
   rewrite the loop in the example -- the finding decides which. Under the flag the boxed
   path pays a guard per ELEMENT, so typing these two loops is worth 3x more there.

## Done

- `gated-delta-rule` under `--gpu --simd` within 1.5x of its `--simd` cost on the same
  JFR method (it is 11x today), the state bit-identical, the 64 tokens unchanged.
- `causal-conv` and `silu-in-place` typed on both arms, or the reason they cannot be
  written into `.kb/jvm-typed-loops.md`, "Not done".
- The device arm's steady forward re-measured the way `718` measured it (the difference of
  a 128- and a 64-token run, both arms, quiet box) and the README's "bf16 weights on the
  device" paragraph updated with the new figure. If the arm then beats `--simd --parallel`
  on this box, the guide's "pick `--simd --parallel` for this program" sentences move too.
- `JvmLispCompilerTest.typedLoopsMatchTheBoxedPathAndTheSizeLevelDeclinesThem` still pins
  the boxed path; add the `--gpu` emission to whatever pins the hoisted materialize today.

## Not in scope

`.todo/725` (the KV cache going up every token) and the printed `tok/s` (`.todo/724`); a
Q4 device width (`718` refused it, and this item is one of its two re-open triggers).
