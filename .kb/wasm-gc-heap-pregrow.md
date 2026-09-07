# `_start` pre-grows the engine's GC heap with one dropped allocation

**Invariant**: the first thing the emitted `_start` body does (Preview 1 and the
component core; NOT `--no-gc`) is allocate and immediately drop a `TYPE_STR_BYTES` byte
array. Size follows the program (`WasmLispCompiler.gcHeapPregrowBytes`):
`GC_HEAP_PREGROW_CODE_FACTOR` (16) x emitted user-function bytes, clamped between
`GC_HEAP_PREGROW_BYTES` (16 MiB floor) and `GC_HEAP_PREGROW_MAX_BYTES` (64 MiB) — except
**serve** mode, always `GC_HEAP_PREGROW_SERVE_BYTES` (1 MiB). Emitted at Pass 2b in
`WasmLispCompiler.compile`, pinned by `WasmGcHeapPregrowTest`.

**The size is a performance knob** (again, since 2026-09-07): from 2026-08-16 to that date
it was held to be a correctness matter, because a `cast failure` that a larger heap made
disappear was read as the collector losing a reference. The defect was Cranelift's
frontend handing a landing pad a pre-collection reference, and the heap size only decided
whether a collection fell inside the throwing call; the wasm backend now sidesteps it
structurally (`.kb/wasm-landing-pad-refresh.md`), so the floor, ceiling and factor may move
on measurement alone.

- Why: wasmtime's copying collector grows only when a SINGLE allocation cannot fit in the
  space a collection frees (`collect_and_maybe_grow_gc_heap`,
  `crates/wasmtime/src/runtime/store/gc.rs`). The heap never shrinks, so one large
  transient allocation permanently buys headroom; the array is garbage before user code
  runs, so RSS is unchanged.
- Not one constant: the live set follows what the program LOADS. 16 MiB covers
  cl-postgres alone, not `rove` on top; on cl-postgres + rove (3.3 MB of emitted defuns)
  26.5 MiB still collects and 32 MiB does not, so factor 16 gives a ~2x margin.

## Sibling knob: the LINEAR memory's declared minimum
`WasmLispCompiler.memoryMinPages`. Rule: **static data plus a heap at least as large as
it** (`HEAP_HEADROOM_MIN_PAGES` = 3 floor). The old fixed ~192 KB exhausted mid-load on
cl-unicode, trapping `out of bounds memory access` with an unnamed backtrace. Both
emission sites take it — the Preview 1 / `--no-wasi` memory section and the component's
`mem` import minimum (which drives `WasmComponentBuilder.memModuleFor`). The bump sites
are unguarded, so it must be right up front. Pinned by `WasmLinearMemoryHeadroomTest`.

## The `cast failure` that a bigger heap hid was not the collector's
On **wasmtime 47.0.3** a boxed local's cell read back as another cell during a NON-LOCAL
EXIT and the next unbox trapped uncatchably (`wasm trap: cast failure`); green under
`-C collector=drc` or a larger `-O gc-heap-initial-size`, trapping under the default
copying collector. That pair of runs says "a moved object was read through a stale
reference", not whose fault it is: the reference was a wasm local that Cranelift's frontend
passed into the landing pad as an exceptional-edge argument evaluated before the throwing
call, outside every stack map (`.kb/wasm-landing-pad-refresh.md`, with the 30-line wat).
`drc` never moves, so it cannot show it; a bigger heap only moves the collection out of
that call. The backend's landing pads no longer read locals that way, and the size here
went back to being about pause time.

## Why serve is different
`_start` runs **once per INSTANCE**, and a served component is instantiated many times
(`wasmtime serve --max-instance-reuse-count`, 128 default; Spin inherits it, wasmCloud
`wash dev` uses 1 — `.kb/tcp-sockets.md`). Growth costs ~**1.5 ms per MiB** on wasmtime
47, so in serve mode the pre-grow is request latency. 1 MiB is the compromise: optimal at
the reuse count every real host uses (+30% native / +27% clack over 16 MiB), ~2% mean
throughput on a never-retired instance; dropping it entirely is worse except at reuse=1.
wasmCloud pools the heap mapping, so the reuse=1 column bounds the SHAPE of the cost, not
its size — measure the host.
