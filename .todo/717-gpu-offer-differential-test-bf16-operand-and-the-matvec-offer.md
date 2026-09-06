# 717. `GpuOfferDifferentialTest`: the bfloat16 operand it cannot build, and the GEMV offer it does not ask

Difficulty: Low

Filed 2026-09-06 while closing `.todo/490`.

`codegen/jvm/GpuOfferDifferentialTest` pins that `eval/LinalgGpu` and
`codegen/jvm/JvmGpuTemplate` -- the two copies of the OFFER decision above `am.ik.gpu` --
accept and decline the same shapes (`.kb/gpu.md`, "The offer is decided twice"). Two gaps,
both now cheap to close:

1. Its `packedBf16(Operand)` throws "the JVM backend's packed representation for the width
   does not exist yet". It has existed since `.todo/485` (`codegen/jvm/JvmPackedFloatWidth`:
   a `short[]` with the two-slot header, data at `1 + 2 * rank`). Build the operand from
   that enum and let a boundary case name `FloatWidth.BFLOAT16`.
2. `vec:matvec` is not among the thirteen predicates it asks, at any width -- so the one
   member that now has THREE width arms on each side (`LinalgGpu.matvec`,
   `JvmGpuTemplate.gpuMatvec` / `gpuMatvecBf16`) is pinned only by the two interceptor
   suites separately. Add it to the table with the pairings: `#d`/`#d`, `#f`/`#f`,
   `#bf16`/`#f` accepted (on the second sight; a first sight declines on both paths, which
   the harness must account for), and `#bf16`/`#bf16`, `#bf16`/`#d`, a mixed f32/f64 pair
   declined.

## Verify

- The census assertion still sees both an accept and a decline per predicate.
- The bf16 rows run on a GPU-less machine as declines (the member-set half), and accept
  on the GB10.
