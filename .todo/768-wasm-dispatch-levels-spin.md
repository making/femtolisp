# `buildDispatch`'s level count can spin forever

Difficulty: Medium

## What happened

A full `./mvnw test` (2026-09-11, aarch64, 16 cores) stopped making progress with
239 of 240 classes reported and `JoseTestSuiteE2eTest` still running. Two
ForkJoin workers were pegged at 100% CPU -- **2223 s of CPU each over 2236 s of
wall clock** -- and every `jstack` sample landed on the same frame:

```
"ForkJoinPool-79-worker-2" ... runnable
  at am.ik.rontolisp.codegen.wasm.WasmRuntimeBuilder.buildDispatch(WasmRuntimeBuilder.java:1973)
  at am.ik.rontolisp.codegen.wasm.WasmLispCompiler.compile(WasmLispCompiler.java:4607)
  at am.ik.rontolisp.e2e.AsdfLibraryE2eSupport.compilesAndRunsOnWasmComponent(AsdfLibraryE2eSupport.java:185)
```

Line 1973 is the radix-depth count:

```java
int levels = 1;
while ((maxFuncId >>> (DISPATCH_PAGE_BITS * levels)) != 0) {
    levels++;
}
```

`>>>` takes its shift distance **mod 32**, and `DISPATCH_PAGE_BITS` is 8. So the
loop terminates only while `8 * levels < 32`: at `levels == 4` the shift wraps to
0, the test reads `maxFuncId != 0`, and the counter runs away forever. Any
`maxFuncId >= 2^24` -- or any NEGATIVE one -- never leaves this loop.

The same class passes in isolation (`./mvnw test -Dtest=JoseTestSuiteE2eTest`:
4 tests, 0 failures, both WASM legs included), and CI runs it in ~82 s, so the
funcId that got in there is not the one a JOSE compile normally produces. The
two legs of `AsdfLibraryE2eSupport` compile CONCURRENTLY (the class carries
`@Execution(ExecutionMode.CONCURRENT)`), which is the part isolation does not
change but a 16-core box's interleaving does.

## What to answer

1. Where does `maxFuncId` come from -- `dispatchTargets(...)`'s `t.funcId()` --
   and what can make one huge or negative? A shared static that the sibling
   compile in the same JVM mutates is the first suspect: two `WasmLispCompiler`
   instances compiling at once is exactly what the harness does.
2. The loop is a hazard regardless of how the value got there: an unreachable
   bound should not be a hang. `levels` cannot exceed 4 for a 32-bit id, so cap
   it (or count with `Integer.numberOfLeadingZeros`) and signal on a funcId that
   cannot be paged.
3. If a race is confirmed, it is a correctness bug well beyond the hang -- a
   compile whose funcIds came from another compile emits a wrong module. Check
   what `WasmLispCompiler` and `WasmRuntimeBuilder` hold statically.

## Verification

- a unit test over `buildDispatch` (or the level count factored out) with
  `maxFuncId` at `2^24`, `2^31 - 1` and a negative value: it must terminate.
- the full suite on a many-core box, since that is what surfaced it; one green
  run is not evidence, so repeat it.
