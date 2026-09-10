# 721. A character `read-sequence` costs about a microsecond per character

Difficulty: Medium

Passed over by two checkpoint lanes in a row (it is adjacent to that path, not on it: the
checkpoint readers use the byte and packed transfers). It wants a quiet box and does not decay,
so it can wait; that it keeps being the item dropped is evidence about the item, not the lanes.

Found 2026-09-06 while closing `.todo/704`. With the accumulate fixed, reading a file
into a string is still dominated by the CHARACTER read itself, and the BYTE path over the
same file is 2x to 23x faster on the same 2,668,890-character file (`.kb/string-accumulate-cost.md`):

|  | interpreter | JVM class | wasm-GC |
|---|---|---|---|
| `read-file-bytes` + `rontolisp:octets-to-string` | 249 ms | 267 ms | 144 ms |
| `uiop:read-file-string` (`read-sequence` into a `make-string` buffer) | 3,694 ms | 539 ms | 3,351 ms |

Reading and DISCARDING the chunks costs 3,254 ms of the interpreter's 3,694, so the cost is
in `read-sequence` and not in what the caller does with the result: about 1.2 us per
character on the interpreter, roughly 1.3 us on wasm-GC. `octets-to-string` decodes the
same bytes at about 25 ns per character, so the gap is not the UTF-8 decode -- it is the
per-character path into the character buffer.

That gap is also why `examples/llm/checkpoint-tokenizer.lisp`'s `read-text-file` still
reads bytes and decodes once instead of calling `uiop:read-file-string`, and why the
ci-spec cost pin for the accumulate could not be written over FILES (a file big enough for
the accumulate to dominate `read-sequence` on the interpreter costs tens of seconds per
backend leg).

## Do

1. Find where the per-character cost is on each backend: the interpreter's
   `read-sequence` character arm (`Environment`), the JVM's `_readSequence`, the wasm
   `read` path. Compare against the byte arm on the SAME file -- the byte arm is the
   existence proof that the I/O itself is cheap.
2. The likely shape is one host read (or one decode step, or one `%schar-set`) per
   character where a bulk transfer would do: the byte arm moves a block into a packed
   array in one go. Decide per backend whether the character arm can decode a BLOCK into
   the character buffer.
3. Re-measure the table above and rewrite it in `.kb/string-accumulate-cost.md`. If it
   closes, revisit two things the gap currently justifies: dropping
   `checkpoint-tokenizer.lisp`'s byte detour, and writing the ci-spec accumulate pin over
   files as `.todo/704` originally asked.
