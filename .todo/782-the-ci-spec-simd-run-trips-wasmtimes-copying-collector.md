# The `ci-spec` `--simd` run trips wasmtime's copying collector

Difficulty: Medium

Measured 2026-09-11/12 (linux-x86-64, 64 cores, wasmtime 47.0.3, `develop` at
`6f63c625c`, binary from `./mvnw -Pnative clean package -DskipTests`):

```
./mvnw -Dtest=CiSpecE2eTest -DfailIfNoTests=false -Drontolisp.binary="$PWD/target/rontolisp" test
```

fails one leg, **`WASM --simd`** (Preview 1, the GC one -- `e2e()[6][1]`), with 3814 tests
run and 1 failure. The other seven legs (interpreter, JVM, WASM component, each with and
without `--simd`, and plain `WASM`) pass.

```
command [wasmtime, --wasm, gc, --wasm, exceptions=y, --dir, ., --dir, /tmp,
         test-simd.wasm, alpha, beta] exited with 1
  1: error while executing at wasm backtrace:
     0:  0x6398b - <unknown>!<wasm function 193>
     1:  0x63aa5 - <unknown>!<wasm function 195>
     2:  0x9dc06 - <unknown>!<wasm function 492>
     3:  0xa9e8d - <unknown>!<wasm function 539>
     4:  0xaca2f - <unknown>!<wasm function 542>
     5:  0xb5b7a - <unknown>!<wasm function 551>
     6: 0x51362c - <unknown>!<wasm function 3275>
     7:   0x4beb - <unknown>!<wasm function 17>
  2: BUG: there should always be enough room in the active semi-space for objects that
     survived collection, since the active space is the same size as the idle space
location: crates/wasmtime/src/runtime/vm/gc/enabled/copying.rs:540
version: 47.0.3
```

## What is known

- **Deterministic.** Two consecutive runs of the same binary failed on the same leg with
  the same message; the second ran with no other maven build on the box, so it is not the
  load that produced it. The plain `WASM` leg (no `--simd`) of the same program passes,
  so it is the `--simd` lowering of the SAME source that crosses the line.
- It is wasmtime's own internal assertion, not a trap our program asks for: the collector
  reports that the surviving set did not fit the space it copies into. Compare
  `.kb/wasm-gc-heap-pregrow.md` -- the `_start` pre-grow exists because the copying
  collector grows only when a SINGLE allocation cannot fit what a collection frees, and
  the pre-grow size follows emitted user-function bytes (factor 16) CLAMPED at
  `GC_HEAP_PREGROW_MAX_BYTES` (64 MiB). A program whose live set outgrows that ceiling
  gets no more headroom however much it emits.
- `ci-spec.yaml` gained cases through 2026-09-11 from `.todo/736`, `763`, `772`, `774` and
  `775`; the driver concatenates every case into ONE program, so the run's live set grew
  on that day. Whether the ceiling, the factor, or a `--simd`-only allocation is what
  gives is NOT established -- nobody has measured the live set.
- **No ordinary run covers this leg**: `CiSpecE2eTest` runs ZERO tests without
  `-Drontolisp.binary`, so `./mvnw test` is green whatever this leg does, and nothing
  dates the regression. Independently reproduced the same day from `.todo/779`'s side
  (two runs, same leg, same message, a native binary built from that item's tree), which
  is also why the fix has to end with the leg reachable -- a size-bounded `--simd` case
  the ordinary suite runs, or a CI job that runs the binary legs -- or it rots again.

## What to answer first

1. Bisect the ci-spec case list, not the compiler: which case's presence flips the leg?
   The driver concatenates in order, so a prefix bisect is cheap and says whether this is
   a size threshold or one case's shape.
2. Measure the live set. `-O gc-heap-initial-size=<N>` on the failing `test-simd.wasm`
   (keep the module: the harness writes it under a `junit-*` temp dir) says how much the
   run actually needs, against the 64 MiB ceiling and the pre-grow the module asks for.
3. Only then decide whether the answer is a bigger clamp, a factor that follows the SIMD
   lowering, or an upstream wasmtime issue. An assertion that says "not thought to be
   reachable" may be wasmtime's bug even when our heap sizing is what reaches it --
   `.kb/wasm-gc-heap-pregrow.md` records the previous time a heap-size symptom turned out
   to belong to Cranelift.

## Verification

- the command above, green on all eight legs, and repeated -- one pass is not evidence for
  a threshold effect.
- whatever the sizing answer is, the numbers and the date go into
  `.kb/wasm-gc-heap-pregrow.md`.
