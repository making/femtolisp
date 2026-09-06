# Turning many pieces into one string costs the TOTAL LENGTH

**Invariant: on ALL FOUR backends, building one string out of N pieces costs the sum of
the pieces, never the sum of the PREFIXES. The two portable ways a program has of doing it
-- `(apply #'concatenate 'string pieces)` and appending into a
`with-output-to-string` stream -- are both linear, and `uiop:read-file-string` is built out
of the second.** A cost invariant, not a semantic one; invisible at the sizes ci-spec and
the doc examples use, which is why regressions here survive.

The sibling of `.kb/string-index-cost.md`: that file is about a character INDEX, this one
about the ACCUMULATE. They were found together on the same file and are separate
mechanisms -- the index was fixed first (`.todo/690`) and the accumulate was what a real
program hit before it ever reached an index (`.todo/704`).

## The two shapes and what each used to cost
Measured 2026-09-06 (JDK 25, wasmtime 47) on a 2,668,890-character file, before -> after:

| shape | interpreter | JVM class | wasm-GC |
|---|---|---|---|
| `uiop:read-file-string` | 6,439 -> 3,498 ms | 18,164 -> **537** ms | (n/a) -> 3,426 ms |
| `read-line` loop + `(apply #'concatenate 'string lines)` | 404 -> 357 ms | 54,197 -> **357** ms | (n/a) -> 3,602 ms |

- **`uiop:read-file-string`** (`src/main/resources/am/ik/rontolisp/eval/uiop-stream.lisp`)
  read 4,096 characters at a time into `(setq acc (concatenate 'string acc chunk))`. Each
  chunk re-copied everything read so far: 3,127 chunks over the real Qwen3.5-0.8B
  `tokenizer.json` (12,807,982 bytes, 10,769,328 characters) is about 1.7e10 character
  copies, and at 134 s it was still inside `_strToCharVec` under `_toMutStr` under
  `READ-FILE-STRING`. It accumulates into a string OUTPUT STREAM now -- a `StringWriter`
  on the interpreter and the JVM, a `$str_bytes` buffer that DOUBLES on both wasm
  backends (`WasmStringStreamRuntimeBuilder`) -- so the chunks are appended, never
  re-copied. It costs nothing extra to reach for: `with-open-file` already puts the wasm
  module in EH mode, so the `with-output-to-string` inside it adds no gate.
- **`(apply #'concatenate 'string lines)`** -- the shape a caller writes by hand -- is the
  FIRST-CLASS `concatenate`, so on the compile paths it runs
  `BuiltinFunctionWrappers.concatenateWrapper`, whose string arm folded pairwise through
  `%string-concat`. 495,920 lines cost about 2.7e12 character copies; at 200 s it had not
  returned. The arm sizes the result ONCE now: `mapcar` normalizes each argument, a
  `reduce` sums the lengths, `make-string` allocates exactly that, and a second `reduce`
  carries the write offset while `replace` copies each piece in. The reduce's own
  accumulator is the offset, so nothing `setq`s a captured variable.
- **The INTERPRETER's `#'concatenate` is the Java builtin, not the wrapper**, and that one
  was already n-ary and linear -- which is why the second row barely moves there. The
  wrapper is a compile-path artifact.
- The in-repo caller of the second shape is `safetensors::%read-text`
  (`src/main/resources/am/ik/rontolisp/eval/safetensors.lisp`), which reads a
  `model.safetensors.index.json` line by line and joins it that way. It was quadratic on
  every compile path and needed no edit: the wrapper is where the cost was.

## The LIST family had the same bug with `append`
`concatenatedElements()` (the wrapper's list and vector arms) folded LEFT --
`(reduce (lambda (a x) (append a (coerce x 'list))) seqs :initial-value nil)` -- and
`append` copies its accumulator, so `(apply #'concatenate 'list lists)` was quadratic in
the total length too. It is a RIGHT fold now
(`:from-end t`, `(append (coerce x 'list) a)`), where each step copies only its own
argument's elements. The `nil` seed is still what copies the LAST argument, so the result
is a fresh list either way and the outer `(append ... nil)` the left fold needed is gone.

## What is deliberately NOT linear
- **The CALL-POSITION `(concatenate 'string a b c)`** still lowers to a nested binary
  `%string-concat` chain (`ConcatenateForms.stringChain`, `.kb/concatenate-result-families.md`).
  The argument count is written in the SOURCE there, so the chain's extra copying is
  bounded by the program text and never by the data; only the n-ary paths -- `apply` /
  `funcall` over a runtime list -- can be handed 495,920 arguments. Change this only if a
  macro is found generating a wide literal concatenate.
- **`(setq acc (concatenate 'string acc chunk))` in a LOOP is quadratic by construction on
  every backend** and no `concatenate` fix can help it: each call copies the accumulator.
  That is a shape to avoid in library Lisp, and the reason `read-file-string` no longer
  uses it. `with-output-to-string`, or `make-string` sized once and filled with `replace`,
  are the two answers.

## The accumulate was never the whole cost of reading a file
`uiop:read-file-string` is still 2x (JVM) to 23x (interpreter, wasm) SLOWER than reading
the same file as bytes and decoding once, on the same 2,668,890-character file:

| | interpreter | JVM class | wasm-GC |
|---|---|---|---|
| `read-file-bytes` + `rontolisp:octets-to-string` | 249 ms | 267 ms | 144 ms |
| `uiop:read-file-string` | 3,694 ms | 539 ms | 3,351 ms |

What remains is the CHARACTER `read-sequence`, which decodes and stores one code point at
a time -- about 1.2 us per character on the interpreter. So **`examples/llm/llm.lisp` and
`examples/llm/checkpoint-tokenizer.lisp` KEEP their byte-reader detour**: `.todo/704` asked
whether they could drop it for `uiop:read-file-string` now, and the numbers say no. That
detour also hands the JVM an immutable `java.lang.String`, which is the representation
`rontolisp:json-parse` is fast over (`.kb/string-index-cost.md`). The remaining gap is
`read-sequence`'s own cost, tracked as `.todo/721`.

## Pinning
- ci-spec `string-accumulate-is-not-quadratic` (all four backends): 4,096 pieces of 64
  characters concatenated in ONE `apply #'concatenate 'string` against the same 262,144
  characters in 256 sixteen-piece calls -> `CONCAT-FLAT`; the same ratio over a
  `with-output-to-string` accumulator -> `STREAM-FLAT`; then `uiop:read-file-string`
  over a 4,500-character file it writes itself, for correctness across the 4,096-character
  read boundary.
  **Why the ratio is not over FILES**, which is what the invariant exists for: on the
  interpreter `read-sequence` costs about 1.2 us per character, so the accumulate does not
  dominate below roughly 1.2M characters, and a file big enough to separate the two
  implementations there costs tens of seconds per backend leg. Measured at 786,432
  characters, one file against 16 files of 48 KB: the old code's ratio is 12.5x on the JVM
  but only 1.53x on the interpreter and 3.8x on wasm -- undetectable under any bound that
  is not itself a flake. The two mechanisms `read-file-string` is MADE of are timed
  instead, at a size every backend finishes in milliseconds.
- `JvmLispCompilerTest#compileANaryConcatenateCostsTheTotalLengthAndNotTheSumOfThePrefixes`
  (6,933 ms under the fold, 39 ms sized once) and
  `WasmLispCompilerIntegrationTest#aNaryConcatenateCostsTheTotalLengthAndNotTheSumOfThePrefixes`
  (19,037 ms -> 23 ms) -- the per-backend cost pins, where a timing verdict cannot take the
  shared corpus down with it.
- Correctness of the rewritten wrapper arms rides the existing
  `#compileAndRunConcatenateAsFunctionValue` /
  `#concatenateBuildsListAndVectorResultTypes` family and the mutable-result pins in
  `.kb/string-write-runtime.md`.

Related: `.kb/string-index-cost.md`, `.kb/concatenate-result-families.md`,
`.kb/string-write-runtime.md`, `.kb/read-load-streams.md`.
