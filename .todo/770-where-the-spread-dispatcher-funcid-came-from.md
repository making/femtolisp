# Where did the spread dispatcher's `2^24` funcId come from?

Difficulty: High

`.todo/768` fixed the HANG (`1fc55d818`: the radix depth is counted from the funcId's bit
length, and a funcId outside `[0, defuns + lambdas)` now fails naming the value). What
made the value is still open. This item is only that question.

## The observation, in full

macOS aarch64, 16 cores, Docker present, `./mvnw spring-javaformat:apply test`,
forkCount=2, JUnit parallelism=16, 2026-09-11.

- The run stopped making progress with **239 of 240 classes reported** and
  `JoseTestSuiteE2eTest` still running.
- **Two** ForkJoin workers pegged at 100% CPU, **2223 s of CPU each over 2236 s of wall
  clock**.
- **Three** `jstack` samples, all three on the same frame in both workers:
  ```
  am.ik.rontolisp.codegen.wasm.WasmRuntimeBuilder.buildDispatch(WasmRuntimeBuilder.java:1973)
  am.ik.rontolisp.codegen.wasm.WasmLispCompiler.compile(WasmLispCompiler.java:4607)
  am.ik.rontolisp.e2e.AsdfLibraryE2eSupport.compilesAndRunsOnWasmComponent(AsdfLibraryE2eSupport.java:185)
  ```
- The two workers are the **Preview 1 and the component leg of the same
  `JoseTestSuiteE2eTest`**, which compile concurrently (`AsdfLibraryE2eSupport` carries
  `@Execution(ExecutionMode.CONCURRENT)`) in ONE fork.
- `./mvnw test -Dtest=JoseTestSuiteE2eTest` alone is green in ~7 minutes, both WASM legs
  included; CI runs the class in ~82 s.

`WasmLispCompiler.compile:4607` is the SPREAD dispatcher's `buildDispatch` call, and line
1973 is the level count in every revision from `ff55fa031` (the last change to the file
before the run) through the run's own HEAD.

## What has been ruled out

The full record, with the numbers, is `.kb/wasm-function-body-size.md` — read it before
adding to this item. In brief:

- **It is the `while` loop, not a mis-attributed line**, and `maxFuncId` really was
  `>= 2^24` in BOTH legs. The live value for that program is 2978 (2453 defuns + 526
  lambdas), ~5600x under it.
- **Not a backend-level race, and not a host-dependent input** (`.todo/768`, 2026-09-11).
- **Not the shared parsed-library caches** (2026-09-11, x86-64 Linux): the front end and
  the backend are both pure functions of their input across DIFFERENT programs in one JVM
  — sequentially, 16-way concurrently, with the interpreter running beside them, and
  across 8 separate JVMs (so not the per-run `ImmutableCollections` salt either). A read
  audit found no path that mutates a cached form list; the caches now hand back
  `List.copyOf`, and `CompileIndependenceTest` pins the output half.

## What is left

**1. Reproduce on the machine.** Every lead that can be chased without the hardware has
been. The ask is a full `./mvnw spring-javaformat:apply test` on aarch64 / 16 cores,
repeated — one green run is not evidence — capturing, when it recurs:

- the `jstack` set (all threads, three samples), and
- whichever way it fails: the `dispatchTargets` exception text if it throws (it names the
  funcId, the bound, the defun count, the lambda count, the arity and whether it is the
  spread dispatcher), or, if nothing throws, those same four numbers taken by hand — that
  is the branch where the POPULATION is what grew, and nothing catches it because every
  bound scales with it.

**2. The recurrence has a narrower search space than it looks.** The arity ladders are
built BEFORE the spread dispatcher, from a SUBSET of its targets, in the same
`buildDispatch`; every one of them completed. So the largest funcId belonged to a callable
that only the spread dispatcher carries: a fixed-arity one whose arity is not in
`indirectCallArities`, or a variadic one whose required count exceeds every built ladder's
arity. Whatever produced it did not produce it for the ladders.

**3. What a dense population would mean.** Every funcId comes from one `nextFuncId[0]++`
and every increment registers a declaration, so a `2^24` id is either a counter that ran
ahead of its declarations (caught) or a compile that really built 16.7M of them
(uncaught). The second would have had to compile 16.7M bodies inside the observed window;
the timeline argues against it but does not exclude it.
