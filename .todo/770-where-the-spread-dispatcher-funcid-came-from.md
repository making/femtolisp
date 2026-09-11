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
  clock** (~1:1, so they spun from near the start of their compile, not after a stall).
- **Three** `jstack` samples, all three on the same frame in both workers:
  ```
  am.ik.rontolisp.codegen.wasm.WasmRuntimeBuilder.buildDispatch(WasmRuntimeBuilder.java:1973)
  am.ik.rontolisp.codegen.wasm.WasmLispCompiler.compile(WasmLispCompiler.java:4607)
  am.ik.rontolisp.e2e.AsdfLibraryE2eSupport.compilesAndRunsOnWasmComponent(AsdfLibraryE2eSupport.java:185)
  ```
- The two workers are the **Preview 1 and the component leg of the same
  `JoseTestSuiteE2eTest`**, which compile concurrently (`AsdfLibraryE2eSupport` carries
  `@Execution(ExecutionMode.CONCURRENT)`).
- `./mvnw test -Dtest=JoseTestSuiteE2eTest` alone is green in ~7 minutes, both WASM legs
  included; CI runs the class in ~82 s.

`WasmLispCompiler.compile:4607` is the SPREAD dispatcher's `buildDispatch` call.

## What has been ruled out (2026-09-11, x86-64 Linux, 64 cores)

- **It is the `while` loop, not a mis-attributed line.** Line 1973 is the level count in
  every revision from `ff55fa031` (2026-09-11 10:21 UTC, the last change to the file
  before the run) to `5946ae22f`; in the revision before that it is a `continue` inside a
  bounded loop, and in the one before that, straight-line emission. Every other loop in
  `buildDispatch` and in everything it calls is bounded by `targets`, `leaves`, `levels`,
  `numCases`, `maxDigit` (<= 255) or `dispatchArgs`. The two that scale with `maxFuncId`
  -- `emitDispatchCases`'s `br_table` label loop -- are FINITE and run BEFORE the level
  count, and at `2^24` they would have finished in about a second (a ~16 MB body) or died
  of memory, not held 100% CPU for 37 minutes. jstack also prints inlined frames as
  frames of their own, so an inlined callee would have shown itself.
- **So `maxFuncId` really was >= `2^24`, in BOTH legs.** The live value is 2978: jose +
  rove + cl-ppcre through `asdf:load-system` builds 2453 defuns + 526 lambdas, 2975
  spread targets, a 260777-byte unpaged body -- over the 128 KiB paging gate, 1367 bytes
  under the 256 KiB body bound, two levels deep. The id that hung is ~5600x that.
- **Not a backend-level race.** `codegen.wasm` holds no mutable static state at all
  (every `static` in the package is a constant or a `static` initializer), funcIds come
  from one `int[]` counter created per `compile()`, and the same two legs compiled **66
  times, 16-way concurrent, in one JVM** are byte-identical to a serial compile and report
  the same `maxFuncId` every time.
- **Not a host-dependent input.** `Features` is machine-independent by construction and
  `BuiltinSystems.announcedFeatures` withholds the trivial-features host half
  (`:darwin`/`:arm64`) from both WASM targets, so the macOS compile reads the same program
  the Linux one does.

## What is left

The suspect is what the isolated run does NOT have: **the ~239 other test classes that
compiled in that JVM first**. Something shared and JVM-lived would have to grow or corrupt
the program JOSE then compiles -- the parsed-library form caches are the shape to look at
(`LispPreludeLibrary.CACHE`, `ShimLibraries.CACHE`, `ClUnicodeTables.CACHE`,
`UiopLibrary.TABLES`, and the `@Nullable private static volatile List<LispVal> forms` in
`GrayStreamsLibrary` / `UsocketLibrary` / `UnreadCharLibrary` / `EnvironmentLibrary` and
their siblings): each hands the CACHED list back rather than a copy, so one pass that
appends to what it was given grows every later compile in that JVM. The 66-compile check
above only exercises JOSE's own splice chain, so it cannot see growth a different
program's chain causes.

Cheap ways in, roughly in order:

1. Hand back unmodifiable views from every `forms()` / `formsFor()` cache and run the full
   suite. A hidden mutation then throws at the mutator, naming it; a green suite says no
   path the suite exercises mutates one, which settles the whole family.
2. Assert in a full-suite run that a fixed program's defun+lambda count is the same when
   compiled first and last in the JVM (an `@Order`ed pair, or a JUnit extension).
3. Reproduce on a 16-core aarch64 box: full suite, repeated. One green run is not
   evidence.

## What the guard from .todo/768 does and does not catch

`dispatchTargets` runs at the top of EVERY `buildDispatch` (every arity ladder, then the
spread one), before any emission, on per-compile state -- so on a recurrence each leg
throws on its own thread with its own message, naming the funcId, the bound, the defun and
lambda counts. That covers the case observed here, where the id is wildly out of step with
the population.

It does NOT catch a compile whose POPULATION is equally corrupt (a program that really
built 16M declarations): the bound scales with it, so the guard passes and the compile
merely gets very large. The timeline above argues that is not what happened -- emitting
16M function bodies would have put at least one of the three samples somewhere else -- but
if the next occurrence throws nothing, that is the branch it took, and the counts are what
to print.
