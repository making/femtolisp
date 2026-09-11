# WASM backend: no single function body may grow without bound

Scope: the GC WASM backend (`codegen.wasm`), Preview 1 and `--component` alike (one
`WasmLispCompiler.compile()`). `--no-gc` is unaffected
([no-gc-scalar-wasm.md](no-gc-scalar-wasm.md)).

## The invariant
**The size of the largest emitted function body decides whether a program can be run at
all.** A wasmtime cold compile (Cranelift) needs memory growing ~superlinearly (~1.8th
power) in the size of ONE body; total module size and function count do not matter.
wasmtime 47.0.2, 4-core Linux amd64: 9 KB body = 284 MB; 261 KB = 2.7 GB; 630 KB =
15.1 GB; 850 KB = 25.8 GB / 36 s.

Bound: **256 KiB**, pinned by `WasmToplevelChunkingTest`, guarded before the fact by
`CiSpecE2eTest.requireRunnableModule` -- each WASM leg inspects the module it just
compiled and refuses to hand wasmtime an over-large one, so the guard costs no extra
compile and covers every leg of the backend x `--simd` matrix. Raise it only with fresh cold-cache measurements
on the smallest CI runner, updating these numbers in the same change.

- **Two bodies grow with the program**: the top level (with source length) and the
  DISPATCH LADDER (with function COUNT).
- **The guard must measure the `--component` build separately** — an async top level
  compiles as an entry+resume pair, so either build can be larger.
  `WasmModuleInspector.largestFunctionBodySize` walks a component's embedded core modules.
- Easy to miss: wasmtime's compilation cache is on by default (`~/.cache/wasmtime`, no
  `--disable-cache` in 47 — delete the dir before each measurement); the failure is not an
  error but a reclaimed 16 GB runner (`The runner has received a shutdown signal.`, no
  stderr, zero exit, peers cancelled), and macOS runners survive the same corpus;
  `ulimit -v` cannot bound it (wasmtime reserves 9-17 GB of address space).

## Keeping the top level bounded
`WasmToplevelEmit.emit` (Pass 2b) closes a chunk once its body passes
`CHUNK_TARGET_BYTES` (48 KiB) and calls it from `_start`; each chunk is an arity-0
`LambdaInfo` called directly, never through the dispatch. The async top level
(`--component` with any `async-defun`/`async-lambda`/`await`) reaches the same chunker via
`WasmAsyncEmit.compileTopLevelChunkedProgn` with `guarded=true` (the resume's `$rt == 0`
guard) — **cutting at the awaits bounds nothing**, since an await-free run is as long as
the program; one `boxedVars` set is computed over the whole run so where it is cut cannot
change how a variable is stored.

Why cuts are safe:
- `WasmLetCompiler` saves and restores `ctx.locals`; block/branch targets, `tagbody`/`go`,
  `handler-case`/`unwind-protect` and special-binding scopes are balanced within one form;
  every form's value is dropped and `_start` returns nothing.
- **`GlobalVarCollector` collects assignments nested at any depth**, not only head
  position, and is deliberately blind to lexical scope. Without this, one nested `setq`
  anywhere would bind a chunk-local a later chunk cannot see.
- **A cut waits while a chunk-bound name is still read later.** `WasmToplevelEmit` cuts
  only where no name bound since the chunk opened occurs again in a later form -- every
  non-quoted symbol counts as a read, so the check can only refuse a cut, never allow a
  bad one. The previous backstop was a latch: the first allocating form disabled every
  later cut, rebuilding the one unbounded body the chunker exists to prevent (.todo/455;
  measured 2026-09-10, no reachable program trips it anymore -- every binder restores,
  and every backend-time expansion that assigns is let-wrapped -- but one future escape
  would have OOM-killed the runner again). A pinning form now only delays cuts past its
  last reader, then cutting resumes.
- `Ctx.definedGlobals` is SHARED by `WasmAsyncEmit.freshCtx`; a per-`Ctx` copy would let
  two chunks initialise one name.
- Chunks are registered during Pass 2b: Pass 2c picks up entries appended while it runs;
  the function section (built later) will not.

## Keeping the dispatch ladder bounded
The SPREAD dispatcher (`WasmRuntimeBuilder.buildDispatch(..., spread = true, ...)`, what
`_apply` calls) is a `br_table` over EVERY callable, ~110 bytes per case, ~410 at its
widest. Past `WasmRuntimeBuilder.DISPATCH_PAGE_BUDGET_BYTES` (128 KiB, half the bound) it
is emitted as a TREE keyed on successive 8-bit digits of the funcId, ~256 cases per leaf,
so **the body no longer depends on the function count** — one extra call per level.
- **Pages are appended after EVERY other function**, so no index moves and a program
  needing none is byte-for-byte unchanged (`.kb/wasm-callable-arity.md`).
- **A page's signature is the dispatcher's own**, so no module gains a type entry: the
  page re-reads the funcId off the closure in local 0.
- **The gate is the emitted body's SIZE, not the callable count**, so the arity ladders on
  the `funcall`/`mapcar`/`sort` hot path stay one call deep.
- **A funcId is always in `[0, defuns + lambdas)`**: one counter hands them out, the
  defuns first (index == funcId), then exactly one per lambda declaration.
  `dispatchTargets` rejects anything else by that bound, because neither dispatcher shape
  can survive a value from outside it — the flat one writes ONE `br_table` label per id
  from 0 up to the largest (a `2^24` id alone is a 16 MB body), and the paged one reads
  the id one 8-bit digit at a time.
- **The radix depth is counted from the bit length** (`WasmRuntimeBuilder.dispatchLevels`,
  pinned by `WasmDispatchPagingTest`), never by shifting the id 8 more bits per round:
  Java takes a shift distance mod 32, so the fourth round of such a loop shifts by 0,
  reads the id straight back and spins forever on any id of `2^24` or more. That loop
  cost one full `./mvnw test` two workers at 100% CPU for 2223 s each (2026-09-11,
  aarch64 16 cores, the two `AsdfLibraryE2eSupport` WASM legs of `JoseTestSuiteE2eTest`,
  which compile concurrently); an unreachable bound must fail, not hang.

Measured 2026-09-11, jose + rove + cl-ppcre through `asdf:load-system`, the widest spread
dispatcher any shipped test builds: 2453 defuns + 526 lambdas, maxFuncId 2978, 2975
targets, 260777 bytes unpaged — over the 128 KiB gate, 1367 bytes under the 256 KiB bound,
and two levels deep. So the id that once reached the fourth level was ~5600x the live
value and came from no counter. **Where it came from is still open (`.todo/770`)**, and
the range check above is what will name it on a recurrence — it runs at the top of every
`buildDispatch`, before any emission, on per-compile state, so each concurrent leg throws
on its own thread. What that check cannot see is a compile whose POPULATION is equally
corrupt: the bound scales with it.

The frame is not in doubt, and neither is the value. Line 1973 is the level count in every
revision from the last change to the file before the run (`ff55fa031`) through the run's
own HEAD; every other loop in `buildDispatch` and everything it calls is bounded by
`targets`, `leaves`, `levels`, `numCases`, `maxDigit` (<= 255) or `dispatchArgs`; the two
that scale with the funcId (`emitDispatchCases`'s `br_table` label loop) are finite and run
BEFORE the count, and at `2^24` they finish in about a second or die of memory rather than
holding 100% CPU for 37 minutes. Nothing in the backend explains the value, either: the
same two legs compiled 66 times 16-way concurrent in one JVM are byte-identical to a serial
compile and report the same funcId every time, `codegen.wasm` holds no mutable static state
at all, and the compile input is host-independent (`BuiltinSystems.announcedFeatures`
withholds the trivial-features host half from both WASM targets). What the failing run had
that an isolated one does not is ~239 other test classes compiling in the same JVM first.

Ask of any new outlining path what the async top level failed: *is the piece it cuts
bounded in BYTES, or only by where some syntactic marker falls?*

## Measuring
`-Drontolisp.wasm.debug-func-sizes` (any value), twin of
`rontolisp.jvm.debug-method-sizes`: one stderr line per SHIPPED function, largest first,
`[func-size] <bytes>\t<final index>\t<name>`, plus a total. Post-shake code-entry bytes;
lambdas and chunks as `_lambda_<id>`/`_toplevel_chunk_<id>`. Component path: the CORE
module. `--no-gc` not covered.
