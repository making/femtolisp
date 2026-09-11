# `nsubstitute` over a vector answers a fresh sequence instead of writing through

Difficulty: Low

`.todo/623` gave the destructive sequence operators a vector/string arm by routing them
through their non-destructive twin -- CLHS lets a destructive operator answer a fresh
sequence, and it beat the silent no-op that was there before. But ANSI expects the
ARGUMENT to change for `nsubstitute` and its `-if`/`-if-not` spellings over a vector:

```lisp
(let ((x (copy-seq #(a b a c)))) (nsubstitute 'b 'a x :count nil) x) ; ANSI wants #(B B B C)
```

Measured 2026-09-11 (`.kb/sequence-bounding-keywords.md`): `NSUBSTITUTE-VECTOR.3`,
`.32`, `.33` and their `-IF` / `-IF-NOT` twins fail this way -- nine tests in the
`sequences` chapter, all reading the argument back after the call.

## How to do it

1. The scan already decides which POSITIONS to replace. For an array/packed-vector
   argument, write the new item into that storage (`Environment.seqResultDestructive` is
   the precedent -- `sort`/`nreverse` keep the argument's fill pointer, adjustable flag
   and identity) instead of building a fresh sequence through `seqResult`.
2. The compile paths take the same route: `deleteOrSubstituteDispatch`'s non-list arm
   currently hands the whole call to `expandSubstitute`. A `replace`-back like
   `seqResultDispatchForm`'s `destructive` mode is the shape that fits.
3. A SOURCE-LITERAL string cannot be written in place (`.kb/string-write-runtime.md`);
   that arm must keep answering the fresh copy, exactly as the destructive dispatch does.
4. `delete` over a vector is the same question and is NOT the same answer: it removes
   elements, so the result cannot be the argument's own storage. Leave it fresh.

Found while closing `.todo/736` (2026-09-11).
