# 786. `read-file-string` is now dominated by its accumulate, not by the read

Difficulty: Low

Found 2026-09-12 while closing `.todo/721`. With the character `read-sequence` moving a block
per host read (`.kb/character-sequence-io.md`), reading a 2,668,890-character file into a
string costs 366 ms on the interpreter, 322 ms on the JVM and 349 ms on wasm-GC -- of which
only 86 / 104 / 62 ms is the READ. The rest is what `uiop:read-file-string`
(`src/main/resources/am/ik/rontolisp/eval/uiop-stream.lisp`) does with the chunks: it
`write-string`s each 4,096-character chunk into a `with-output-to-string` accumulator, so
every character is touched three times -- stored into the buffer, copied into the stream, and
rendered into the result -- where two would do.

## Do

1. Try the sized-once shape instead: one `make-string` buffer that DOUBLES, filled by
   `(read-sequence buf in :start len)`, answered as `(subseq buf 0 len)`. Two touches plus an
   amortized copy.
2. **Check the RESULT REPRESENTATION before believing any timing.** The current accumulator
   answers an immutable `java.lang.String` on the JVM, and `subseq` of a character vector
   answers a MUTABLE character vector (`_subseqCv`, `.kb/string-write-runtime.md`). If the
   rewrite flips the representation, re-measure `rontolisp:json-parse` over the same file
   (`.kb/string-index-cost.md`) before keeping it -- a faster read that hands the parser a
   slower string is not a win.
3. The accumulate invariant and its pins are `.kb/string-accumulate-cost.md`; the cost pins
   this item would move are the `read-file-string` rows in `.kb/character-sequence-io.md`.
   Re-measure both on all four backends and rewrite the rows.
