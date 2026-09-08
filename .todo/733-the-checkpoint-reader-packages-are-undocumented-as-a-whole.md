# The checkpoint-reader packages are documented one function at a time and nowhere as a whole

Difficulty: Medium

`gguf`, `safetensors`, `checkpoint` and `tokenizer` each have a per-function
reference page and a package page, and `doc/*/nav.yaml` lists all four. But:

- **`reference/functions.md`'s "Packages" table does not list any of them.**
  The table is the page a reader lands on from the Language Reference, and it
  names 15 packages; these four are missing from it, so the only route to
  `functions/gguf.md` is the sidebar. `cl`, `rontolisp`, `linalg`, `torch`,
  `java`, `ffi`, `objc`, `appkit`, `geom`, `metal`, `scene`, `asdf`, `uiop`,
  `ql`/`ql-dist` and `usocket` are there; `checkpoint`, `safetensors`,
  `tokenizer` and `gguf` are not. Both languages carry the same table.

- **No guide covers the whole path.** `guides/` has a page for every other
  cross-cutting surface -- `linalg`, `torch`, `geom`, `--gpu`, `--blas`,
  `objc`/`appkit`, WIT, the WASM outputs. Reading a published checkpoint has
  none, so the four package pages describe the parts and nothing says how they
  compose: pick a checkpoint, read its metadata without touching the weights,
  build the tokenizer the file carries, stage the tensors at a width the
  backend supports, and run it. Today that knowledge lives only in
  `examples/llm/README.md`, which is an example's page, not a guide.

Nothing here is blocked on an unimplemented feature: `gguf:read`,
`safetensors:read`, `tokenizer:make-bpe` / `make-sentencepiece`,
`checkpoint:stage-float-bits` and the `rontolisp:quantize` path all exist and
are exercised by `examples/llm/llm.lisp` on all four backends.

## What to do

1. Add the four rows to the "Packages" table in `doc/en/reference/functions.md`
   and `doc/ja/reference/functions.md`, in the same order the sidebar uses, each
   with the one-line description the package page's own opening states.

2. Write `doc/{en,ja}/guides/running-a-checkpoint.md`: the end-to-end path from
   a published checkpoint to generated text. Cover, in the order a reader meets
   them -- what the two container formats are and which one a given publisher
   ships; the metadata-only read and why it is free; the tokenizer the
   checkpoint carries and the two vocabulary kinds; the weight widths that load
   (F32/F16/BF16 into packed float arrays, Q8_0 into a quantized matrix,
   everything else refused when its body is asked for) and which backends each
   works on; the chat template question (a checkpoint's own template is the
   authority -- SmolLM2's injects a system turn `*chatml*` does not); and where
   the worked engine is (`examples/llm/`), without restating it.

3. Register the new page in `doc/en/nav.yaml` and `doc/ja/nav.yaml` under
   Guides, in a position that matches the surface's neighbours.

4. Every ```lisp fence is run by `DocExamplesTest` -- give each one a `; =>`
   result that holds without a downloaded checkpoint, or write the
   checkpoint-dependent snippets in a fence `DocExamplesTest` does not execute
   (see how the other guides handle a snippet that needs a device or a file).

## Do not

- Do not duplicate `examples/llm/README.md`. The guide is about the packages;
  the README is about that program. Each links to the other once.
- Do not add anything to the per-function pages. They are complete.
- `doc/en/**` and `doc/ja/**` move in the same commit: same file set, same
  headings, byte-identical code fences (`CLAUDE.md`, "Documentation Site").

## Done when

- `./mvnw -Dtest=DocExamplesTest test` is green.
- `./mvnw -f docs-tool/pom.xml test` is green (nav layout changed).
- The rendered `reference/functions.html` links all four packages.
