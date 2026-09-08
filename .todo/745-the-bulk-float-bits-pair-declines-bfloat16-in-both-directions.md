# 745. The bulk float-bits pair declines `bfloat16` in both directions, and its owner is closed

Difficulty: Medium

`rontolisp:widen-float-bits` and `rontolisp:narrow-float-bits` are the bulk
conversion between a packed `(unsigned-byte 16)` vector of `:float16` /
`:bfloat16` bit patterns and a packed float array -- the primitive a checkpoint
reader stages a tensor through. Both refuse the `#bf16` array:

- `eval/FloatBitsWidening.widen`, `case LispBFloat16Array` --
  `"does not yet write a bfloat16 destination"`
- `eval/FloatBitsWidening.narrow`, `case LispBFloat16Array` --
  `"does not yet read a bfloat16 source"`
- `codegen/jvm/JvmFloat16RuntimeBuilder` emits both messages word for word, so
  the JVM backend answers exactly what the interpreter does.

Both are TEMPORARY refusals by `.kb/bfloat16.md`'s own taxonomy -- a
`LispEvalException` whose message says "does not yet", which is the shape
reserved for a width that is expected to arrive. The comment at each site names
`.todo/487` as the owner. **`.todo/487` is closed** (2026-09-05), so the arm has
been sitting behind a closed item; `.kb/bfloat16.md` records the same gap and
cites the same closed number. Nobody skipped a step -- this is rule 1: only the
closer can write back a dependency, and a "does not yet" left in the tree reads
as scheduled when it is not.

The wasm backends are not in this: they refuse the `#bf16` width BY NAME at
`compiler/UnsupportedFloatWidth`, so no bfloat16 destination can exist there to
convert into. This item is the interpreter and the JVM.

## What it does and does not cost today

**Not the checkpoint path.** A BF16 safetensors or GGUF tensor already loads with
no conversion at all: `read-sequence` / `write-sequence` move a bf16 array in ONE
bulk transfer of its STORED PATTERNS, two little-endian bytes an element, which
is what the file holds (`.kb/bfloat16.md`). That route round-trips byte for byte,
signalling NaN payloads included, and it is why the width shipped without this arm.

What is missing is the pattern-to-array direction where the two widths DIFFER:

- `:float16` patterns into a `#bf16` destination -- one conversion, and the only
  route a published F16 checkpoint has into the narrow width. Today it must widen
  into `#f` and then narrow, which allocates the f32 array the width exists to avoid.
- `:bfloat16` patterns into a `#bf16` destination -- a straight copy, and the
  `:start`-offset shape `checkpoint.lisp` uses for a chunked read.
- a `#bf16` source in `narrow-float-bits` -- writing a bf16 array back out as
  `:float16` patterns.

## Do

1. Fill the three arms. The `:bfloat16` -> `#bf16` case is a copy of the stored
   patterns; the `:float16` -> `#bf16` case converts once, and `:bfloat16` out of a
   `#bf16` source is a copy in the other direction. Every loop runs against the raw
   `short[]` backing through `LispFloatArray.storage()` / `data()`, as the existing
   arms do -- not the boxed `elementAt` / `setElement`, which is orders of magnitude
   slower and is what the primitive exists to avoid.
2. Mirror it in `codegen/jvm/JvmFloat16RuntimeBuilder`, whose emitted helper answers
   the same thing the interpreter does today and must keep answering the same thing.
3. **The `:float16` -> `bfloat16` conversion must not become a fourth copy of the
   rounding.** Route it through `am.ik.rontolisp.BFloat16` on the interpreter and
   through whatever the JVM backend already emits for `bfloat16-bits`; `.todo/689`
   is the sibling item about the third copy, and every copy of this arithmetic that
   broke on 2026-09-03 broke in the narrow / NaN direction, in a copy.
4. Pin it the way the width is pinned elsewhere: all 65536 patterns in the widen
   direction, and for the narrow direction the NaN space the existing bf16 tests
   cover (`JvmBFloat16ArrayTest` is the shape).
5. Update `.kb/bfloat16.md`'s "The bulk `widen-float-bits` / `narrow-float-bits` pair
   still declines a bf16 source or destination" line, and drop the dead `.todo/487`
   citation from the two source comments.

## Do not

- Do not widen this into "the whole `#bf16` surface". `.todo/689` (the `jvm-export`
  handle) and `.todo/696` (the element-wise kernels) are the other two open bf16
  arms and are separately owned.
- Do not add the width to the wasm backends here. That is a different decision and
  `UnsupportedFloatWidth` is refusing correctly.

## Done when

- Both operators accept a `#bf16` source and destination on the interpreter and the
  JVM, and the two "does not yet" messages are gone from the tree.
- `./mvnw test` is green, and the native E2E if `ci-spec.yaml` gains a case.
