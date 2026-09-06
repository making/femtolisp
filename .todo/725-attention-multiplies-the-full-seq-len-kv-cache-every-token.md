# 725. The attention GEMVs run over the full seq-len KV cache every token

Difficulty: Medium

Filed 2026-09-06 by `.todo/718`'s decode profile (GB10, Qwen3.5-0.8B, `-w bf16`; the
record is `.kb/gpu.md`, "The GEMV, and the matrix that stays").

## What was seen

`attention` in `llm.lisp` scores a head with `(vec:matvec kch qh)` over the WHOLE cache --
`kch` is seq-len x head-size, 4096 x 256 f32 = 4 MB for this model -- and reads the values
back with `(vec:matvec vth att)` over the whole transposed cache, relying on the rows past
`pos` being zero and `att` being zero there. Correct, and for stories15M (seq-len 256,
48 KB a head) invisible. For a published checkpoint the zeros are most of the bytes:

- **On the CPU**: 48 GEMVs of 4 MB a token, 192 MB, whatever `pos` is. On the `--simd`
  arm that is `matvecRowsF` at 11.8% of the decode window = **8.9 ms of an 81 ms forward**
  on one thread (JFR); at 16 threads the parallel kernel takes it to ~1 ms of 24.
- **On the device**: the cache is written every token (the append) and read by the four
  query heads that share it, so the two-sight rule runs first sight -> upload -> hit -> hit
  every token: **24 uploads of 4 MB a forward (96 of the 102 MB that go up), 2.4 ms of
  `cuMemcpyHtoD` on the calling thread plus 1.9 ms of copy on the device**, 36 f32 GEMV
  launches (0.7 ms), and the 12 first sights computed by the CPU lane kernel, 3.5 ms. Some
  6 ms of a 45 ms forward for an attention that touches 4 KB of live keys a head.

## Do

Bound the product to the live rows. The cleanest shape is a row-count argument or a
row-bounded member (`vec:matvec` over the first `n` rows of `W`, `vec:matvec-into` with the
same bound) that every backend implements on the scalar `vec.lisp` definition, the lane
kernel, the parallel split, the BLAS route (`cblas_?gemv` takes `m` -- it is free there) and
the device (the offer's extent). A displaced or adjustable view is the other route and
costs more surface. The transposed value cache is a column bound, not a row bound, so the
second product either stays whole or the layout changes -- measure before choosing; on
short contexts the value read is a strided walk either way.

Under the device the bounded matrix is small until `pos` is large, so the KV GEMVs decline
by size and the 96 MB stops going up; whether the residency rule should treat an array
written between calls but read several times within one call differently is a separate
question, and this item does not answer it.

## Done

- `examples/llm` decodes Qwen3.5-0.8B with the same 64 tokens and the attention cost
  proportional to `pos`, measured the way `718` measured (`decode-jfr-agg.py`'s
  `matvecRowsF` share on the `--simd` arm; `decode-per-token.py`'s HtoD bytes a forward on
  the device arm).
- The stories byte-identical on all four backends (`ExamplesE2eTest -Drontolisp.examples.only=llm/`).
- If a `vec:` member changes shape: `.kb/vec.md`, the reference page, `ci-spec.yaml` under
  the `--simd` axis, both WASM backends.

## Not in scope

The residency guards (`.todo/723`) and the printed `tok/s` (`.todo/724`).
