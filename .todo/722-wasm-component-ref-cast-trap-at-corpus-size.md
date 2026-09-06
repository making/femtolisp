# 722. The WASM COMPONENT backend traps on a `ref.cast` that a one-line source edit moves

Difficulty: High

Found 2026-09-06 while working `.todo/687`, which had to change `linalg::%la-make` and found
that HOW the change is spelled decides whether `CiSpecE2eTest` passes.

## What happens

`ci-spec.yaml`'s 480 cases, concatenated the way `CiSpecE2eTest` concatenates them, compiled
with `--component` and run under `wasmtime 47.0.3`, trap:

```
Error: failed to run main module
  0: error while executing at wasm backtrace:
    0:   0xc2f4 - <unknown>!<wasm function 69>
    1: 0x1f265c - <unknown>!<wasm function 1378>
    2: 0x2a2c73 - <unknown>!<wasm function 2159>
    3: 0x2a2cac - <unknown>!<wasm function 2160>
    4: 0x1d121d - <unknown>!<wasm function 1295>
    5: 0x2af0df - <unknown>!<wasm function 2283>
    6: 0x4f9d8a - <unknown>!<wasm function 3208>
    7: 0x3a1aa5 - <unknown>!<wasm function 3052>
    8:   0x5a75 - <unknown>!<wasm function 20>
  1: wasm trap: cast failure
```

The program has printed 3587 of its 3590 lines by then. The trap is inside
`(gguf:read "ci-model.gguf" :only '("q40"))` -- the call whose only job is to SIGNAL, because
the reader does not load Q4_0. Everything before it, the whole `linalg:` and `vec:` corpus
included, has already answered correctly.

**Only `--component` traps.** The same corpus, same compiler, Preview 1 (`-o test.wasm`), runs
to completion. So does the interpreter, the JVM and the native binary. `--optimize` does not
change it.

## What moves it, and what does not

The lever is not the source of the failing call. It is the SHAPE OF AN UNRELATED FUNCTION, and
these were all measured on 2026-09-06 against the same corpus:

| `linalg::%la-make`'s body | component run |
| --- | --- |
| develop's (a `when`-guarded `error`, then a two-arm `if`) | passes |
| that `if` with a third `make-array` arm added, guard kept | passes |
| the guard's `error` DELETED, nothing else changed | **traps** |
| the guard replaced by `(when (eq element-type 'ci-never-a-width) (print init))` | passes |
| the guard replaced by `(when (eq element-type 'ci-never-a-width) (error "x"))` | passes |
| a three-arm `cond`, `single-float` first | **traps** |
| the SAME three-arm `cond`, `bfloat16` first | passes |
| nested `if`s, `single-float` first | **traps** |

The last two rows are the same code in a different order and the same size. So this is not a
size threshold, and it is not about `bfloat16`, and it is not about throwing: it is the byte
layout of the emitted module.

What does NOT move it:
- padding the PROGRAM with extra defuns, or with extra string constants (still traps);
- adding an unused defun to `linalg.lisp` (still traps);
- `--optimize` (still traps).

What does move it:
- dropping ONE case from the corpus. `cases[0..477] + gguf` passes; `cases[0..478] + gguf` (the
  whole corpus) traps. The case that tips it is `tokenizer-cross-backend`, and nothing about
  its subject matters -- it is 2956 characters of bulk.
- the WORKING DIRECTORY. The identical module traps in an empty directory and passes in one
  left over from an earlier run. (An earlier corpus case counts directory entries, so a run in
  a dirty directory takes a different path somewhere; that is a second lever on the same
  latent defect, not a second defect.) **A reproducer must therefore run in a fresh
  directory** -- which is what `CiSpecE2eTest` does, so CI sees the trap and a careless manual
  re-run does not.

## What has been ruled out

- **The adapter.** The passing and trapping components differ for the first time at byte 5266,
  immediately before the third core module. The two WASI adapter core modules (at 2438 and
  2599) are BYTE-IDENTICAL, so `WasmComponentBuilder.fixedSurface`'s narrowing and
  `WasmTreeShaker.shake` of `ADAPTER_MODULE` are not involved.
- **`WasmBodyFolder`.** It runs only at the tail of `WasmTreeShaker.shakeWithRemap`, which the
  component path applies to the adapter and memory modules only -- never to the program's own
  core module, which is where the difference is.
- **Function/type counts.** Both core modules declare 3228 functions and 91 type entries. Only
  the code bytes differ (5479061 passing, 5478810 trapping) -- and the trapping one is SMALLER.
- **The signalling itself.** Replacing the failing call with `(error "boom")`, or with the same
  four-piece `(concatenate 'string ...)` control string and the same two `~a` arguments, passes.
  Replacing the handler's body with a constant still traps. So the trap is inside
  `gguf:read`'s own call chain, not in `error`, `format` or `handler-case`.
- **`gguf:` in isolation.** The `gguf-cross-backend` case compiled on its own, with a `linalg:`
  call in front of it to force the splice, passes on the component backend.

## Reproduce

```bash
./mvnw -o package -DskipTests
python3 - <<'PY'                      # the driver's own concatenation
import pathlib, yaml
spec = yaml.safe_load(pathlib.Path("src/test/resources/ci-spec.yaml").read_text())
src = "".join(c["source"] if c["source"].endswith("\n") else c["source"] + "\n" for c in spec["cases"])
pathlib.Path("/tmp/ci-program.lisp").write_text(src)
PY
rm -rf /tmp/cirun && mkdir /tmp/cirun          # a FRESH directory, or it will pass
java -jar target/rontolisp-0.1.0-SNAPSHOT-exec.jar /tmp/ci-program.lisp \
     -o /tmp/cirun/p.component.wasm --component
cd /tmp/cirun && wasmtime run -W gc=y -W exceptions=y --dir . --dir /tmp p.component.wasm alpha beta
```

Then reorder `linalg::%la-make`'s `cond` arms (`single-float` first) and repeat.

## Why it matters more than one corpus

The corpus sits ONE case away from the cliff, and the cliff is invisible from the source: a
change to any spliced library can land on either side of it, and what the author sees is a
`cast failure` in a `gguf:` case they did not touch. `.todo/687` shipped with the arm order
that passes and a comment saying so, which is a coin landing the right way up, not a fix.
Nothing here is `linalg:`'s to own.

## Do

1. **Name the function.** The backtrace has indices and no name section. Emit a `name` custom
   section behind a flag (the tree shaker already knows to drop one, `WasmTreeShaker` line 412),
   or map index 69 by hand through the import count and the code section. Everything else is
   guesswork until that frame has a name.
2. Then find the `ref.cast` it executes and what reaches it. The suspicion the evidence
   supports is a type or index the COMPONENT path computes differently from Preview 1 --
   the async I/O lowering is the only thing the two do not share -- and that is only wrong for
   some layouts.
3. A regression pin has to be a whole-corpus one; nothing smaller reproduces. `CiSpecE2eTest`
   already is that pin, which is why this was found at all.

## Verify

- The reproducer above passes with `single-float` first AND with `bfloat16` first.
- `linalg::%la-make`'s comment about the arm order comes OUT when this is fixed.
- Preview 1, the JVM, the interpreter and the native binary are unchanged (they never trapped).
