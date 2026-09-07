# 732. A row slice of a quantized matrix without a scratch file

Difficulty: Medium

Filed 2026-09-07 by `.todo/728`.

`examples/llm`'s `split-gated-q` has to split Qwen3.5's `attn_q` (`query | gate` per head) into
two matrices. For a packed float source it is an element loop; for a Q8_0 source
(`rontolisp:quantized-matrix`, immutable, no `(setf aref)`) the only exact route today that the
program may name is a BYTE COPY through `write-sequence` / `read-sequence` with byte-counted
`:start` / `:end` -- and since `file-position` does not seek (`gguf.lisp`), through a scratch file
in `$TMPDIR` (`split-gated-q-blocks`). `rontolisp:dequantize` -> split -> `rontolisp:quantize` is
exact too (verified: the blocks round-trip byte for byte, since ggml's quantizer maps a block's
largest element to +-127), but a program that also compiles to WASM cannot NAME either
(`.kb/quantized-matrix.md`, "Refusals": permanent compile-time refusals, even in a guarded arm), and
`examples/llm` does.

## Do

Give the type a row-range view or copy that every backend carrying it can spell without a file:
either `subseq`-like rows (`(rontolisp:quantized-rows m start end)`, a fresh matrix of the blocks
of those rows -- 34 * cols / 32 bytes a row, one `System.arraycopy`) on the interpreter and the JVM
with the WASM call-time signal of `make-quantized-matrix`, or `read-sequence` from an in-memory
octet stream the program can build without `flexi-streams` (which is spliced only for a system that
depends on it). Then `split-gated-q-blocks` drops the scratch file, and the GGUF reader could hand
tensors out by row range where a model wants them split.

## Not in scope

The Q8_0 kernels themselves; Q4 widths.
