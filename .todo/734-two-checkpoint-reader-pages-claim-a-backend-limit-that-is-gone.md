# Two checkpoint-reader pages claim an F16/BF16 backend limit that is gone

Difficulty: Low

`safetensors:read` and `checkpoint:stage-float-bits` each end with a "Backend
support" section that says the read is waiting on `rontolisp:widen-float-bits`
reaching the WASM backends:

- `doc/{en,ja}/reference/functions/safetensors-read.md`: "the interpreter and a
  compiled `.class`/`.jar` today; the WASM backends once
  `rontolisp:widen-float-bits` is there (F32 tensors need no widening and read
  everywhere)."
- `doc/{en,ja}/reference/functions/checkpoint-stage-float-bits.md`: "Every
  backend that has a filesystem, once `rontolisp:widen-float-bits` is there:
  the interpreter and a compiled `.class`/`.jar` today."

Both are stale. `rontolisp:widen-float-bits` is compiled by
`WasmExprCompiler` (`LispNames.WIDEN_FLOAT_BITS`, lowered by
`WasmFloat16Compiler`), `.kb/checkpoint-readers.md`'s invariant says "same
arrays on every backend, with and without `--simd`", and
`examples/examples.yaml` runs `llm/safetensors-check.lisp` -- whose fixture is
"F32, F16 and BF16 tensors of rank 1, 2 and 3" -- on
`[interpreter, jvm, wasm, wasm-component-run]`, twice each (default and
`--simd`). So the F16/BF16 read works on all four today.

What IS still interpreter-and-JVM-only is narrower and both pages state it
correctly elsewhere: the `'bfloat16` element TYPE, and the Q8_0 quantized
matrix.

## What to do

1. Rewrite the "Backend support" section of both pages (four files, en and ja
   in the same commit) to say the read runs on every backend that has a
   filesystem, full stop, and that the remaining limits are the `'bfloat16`
   destination width and -- for `gguf` -- the Q8_0 quantized matrix.
2. Grep the rest of `doc/**` for the same "once `rontolisp:widen-float-bits` is
   there" phrasing and any other claim that F16/BF16 staging is JVM-only; fix
   what is found in the same commit.
3. `doc/{en,ja}` move together: same file set, same headings, byte-identical
   code fences.

## Done when

- `./mvnw -Dtest=DocExamplesTest test` and `./mvnw -f docs-tool/pom.xml test`
  are green.
- No page claims a WASM limitation on the F16/BF16 read.
