# 758. The four `--simd` backends disagree on an f32 reduction whose length is not a multiple of 4

Difficulty: High

Found 2026-09-10 while closing `.todo/480` (the multi-accumulator GEMV row), from the one
column count that item had never asserted on the wasm side.

`.kb/vec.md` states the reduction contract as "every `--simd` backend accumulates an f32
reduction in f32 and promotes ONCE at the value boundary, so all four agree", and pins
`FSPECIES_REDUCE = SPECIES_128` so the lane COUNT cannot differ. The lane count is not the
only thing that decides the fold. **The four implementations close a reduction's last,
partial group differently:**

- the interpreter, the JVM class and `--no-gc` run the lane loop to `loopBound(n)` and
  finish the leftover elements as a SCALAR TAIL, in index order, added to the folded lane
  sum;
- wasm-GC has no scalar tail at all (by design -- `.kb/vec.md`, "No kernel has a scalar
  tail"): a packed array is `ceil(n/4) + 1` v128 groups with the padding zeroed, so it
  folds `ceil(n/4)` groups and the padding lanes contribute exact zeros.

Both are correct in exact arithmetic and the padding really is harmless to the VALUE. It
is not harmless to the BITS: the last elements are added at a different point in the sum,
so an f32 reduction near a rounding boundary lands on different neighbours.

## The reproducer (measured, this box, `62708bcce` + `.todo/480`'s test additions)

A 1x31 single-float GEMV with `2^24` in the tail region, `--simd` on every backend:

```lisp
(let ((m (make-array '(1 31) :element-type 'single-float :initial-element 1.0))
      (v (vec:ones 31 :element-type 'single-float)))
  (setf (aref m 0 29) 4096.0)
  (setf (vec:aref v 29) 4096.0)
  (print (round (vec:aref (vec:matvec m v) 0))))
```

| leg | answer |
| --- | --- |
| scalar reference (any backend) | 16777246 (exact: `2^24 + 30`) |
| interpreter `--simd` | **16777244** |
| JVM class `--simd` | **16777244** |
| wasm-GC `--simd` | **16777248** |
| wasm component `--simd` | **16777248** |

**It is not the GEMV and not `.todo/480`'s gate** -- 31 columns is below
`MATVEC_ACC_THRESHOLD`, so every implementation is running the single chain it always ran.
`vec:dot` diverges the same way at 131 elements with `4096.0` at index 127: interpreter and
JVM `--simd` answer 16777344, wasm-GC answers 16777348.

## Why no test saw it

Every cross-backend `--simd` probe uses a length that is a MULTIPLE of the lane count --
1024 for the reduction pins, 16 / 24 / 32 for `.todo/480`'s gate pins -- where the two
strategies coincide exactly because there is no partial group. `.kb/lanes-and-certification.md`'s standing
rule 6 in its general form: every case sits on one side of the condition.

`.todo/480` asserts 31 columns on all four (`2^24 + 24` everywhere) and that agreement is
REAL but accidental: the two folds reach different intermediate sums and both tie to even
onto the same neighbour for that probe's data. A pin at a partial length is therefore not
evidence of agreement unless the data is chosen to be sensitive to the order.

## What has to be decided

This is a contract question before it is a code change, which is what makes it High.

- **Which fold is the contract?** Giving wasm-GC a scalar tail costs it the property the
  layout was built for (and the `+1` sentinel group, the shuffle window and
  `gcSaveLastGroup` all assume the padded shape). Giving the JVM and interpreter a padded
  final group means materialising a zeroed group per reduction, or masking, on the hot
  path.
- **What re-pins:** every `#f` probe in `eval/VecSimdTest`, `JvmSimdAccelCompilerTest`,
  `WasmLispCompilerIntegrationTest`, `.kb/vec.md`'s reduction table, and whatever the
  change moves in `linalg`. The bf16 and Q8_0 arms follow the f32 arm by their own
  contracts (`.kb/bfloat16.md`, `.kb/quantized-matrix.md`).
- **Whether it is worth it at all** is a real option: the divergence is a last-bit one, it
  has been shipping since the wasm-GC kernels landed, and no model output has ever moved on
  it (greedy argmax absorbs it, as `.todo/480` re-verified on eight legs). The alternative
  close is to write the exception INTO the contract -- "the four agree at lengths that are
  multiples of the lane count, and may differ in the last bit otherwise" -- and to pin that
  boundary deliberately with an order-sensitive probe on each side, so the next reader is
  not told something false. **A measurement-free close is not available either way: what
  the contract says today is not what the code does.**

## Acceptance

Either the four `--simd` implementations agree bit for bit at a length that is NOT a
multiple of the lane count, pinned with an order-sensitive probe (the reproducer above is
one), or `.kb/vec.md` states the exception exactly and a test pins both answers so a
future change to either fold is visible. In both cases the reproducer runs on all four
backends.

## Related

`.todo/759` is the same SHAPE and a different mechanism: a `--simd` lane form admitted on
the strength of "it equals the defun", where the equality holds over the inputs every probe
used and not over the domain. There the divergence is `vec:sqrt` on a negative input (CL
`sqrt` is complex-extended and the lane form is not); here it is the last bit of an f32
reduction at a length no probe used. Both were found by asking what the pins did NOT cover,
and neither is caught by adding cases of the shape already there.
