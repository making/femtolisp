# Shrink the wasmtime semi-space bug to a `.wat` and report it upstream

Difficulty: Medium

`.kb/wasm-gc-heap-pregrow.md`, "The size is drawn from a lottery", records a wasmtime
47.0.3 defect our emitted modules reach: at narrow BANDS of GC-heap size the copying
collector either injects

```
BUG: there should always be enough room in the active semi-space for objects that
survived collection, since the active space is the same size as the idle space
crates/wasmtime/src/runtime/vm/gc/enabled/copying.rs:540
```

into the guest, or panics outright with `invalid VMGcKind: 0b0` -- a ZEROED object header,
i.e. the collector walked into memory nothing ever wrote. wasmtime says the state is "not
thought to be reachable" and asks for a report.

The compiler side is closed: the pre-grow size is quantized to 16/32/64 MiB so a corpus
edit can no longer move it onto a band (`WasmGcHeapPregrowTest.pregrowSizeIsAlwaysAPowerOfTwo`).
What is NOT closed is the engine bug, which any user program that makes one large
allocation can still reach; nothing in our sizing rule protects a program that allocates
a 60 MB array of its own.

## What to do

1. Reproduce: compile the `ci-spec` corpus `--simd` with the pre-grow forced to
   65,259,152 bytes (the value the formula produced on 2026-09-11) and run it under
   `wasmtime --wasm gc --wasm exceptions=y`. The knobs and the measured bands are in the
   `.kb` file; a temporary system-property override on
   `WasmLispCompiler.gcHeapPregrowBytes` is how the sweeps were taken.
2. Shrink it. `wasm-tools shrink` takes a predicate script -- "exits non-zero with
   `semi-space` in stderr" -- and cuts the module down. Budget: a failing iteration is
   ~1 s, a green one ~30 s on a 9 MB module, so this wants an overnight run and a
   predicate that fails FAST.
3. File the issue against bytecodealliance/wasmtime with the shrunk module, both symptoms
   (the injected trap and the `VMGcKind` panic), and the sharp `-O gc-heap-initial-size`
   threshold: 131,072 dies, 139,264 is green, on a 62 MiB heap.
4. Write what the shrunk module shows back into `.kb/wasm-gc-heap-pregrow.md` -- in
   particular WHICH object the collector fails to place, which the black-box sweeps could
   not answer.

## Why it is worth the run

The `.kb` entry currently reasons from the outside: the survivor set overflows by less
than 8 KiB of a 62 MiB space, which points at the ~62 MiB pre-grow array being counted as
a survivor, while a small program with the same pre-grow demonstrably collects it. Either
that inference is right and Cranelift is keeping a dropped value live at one safepoint
shape (compare `.kb/wasm-landing-pad-refresh.md`, where a heap-size symptom turned out to
belong to Cranelift), or it is wrong and the collector's own sizing is off. A shrunk
module answers it; nothing short of one has.
