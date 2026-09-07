# 729. The native binary's downcalls through SubstrateVM's own AOT route: 10.7 ns where FFM costs 2-7 us

Difficulty: High

Filed 2026-09-07 by `.todo/727`, which attributed the binary's FFM downcall cost to SubstrateVM's
method-handle interpreter (`.kb/gpu.md`, "An FFM downcall inside a native image costs";
`.todo/artefacts/727-an-ffm-downcall-costs-2-1-us-inside-a-native-image/`, sections 1-3).

## The measurement

The same `cuDriverGetVersion` address, in the same image: an FFM downcall handle is **2104 ns** a
call; an `@InvokeCFunctionPointer` interface (`org.graalvm.nativeimage.c.function`) compiled at build
time and given the address at run time is **10.7 ns** with the thread transition and 3.2 without.
The shapes this repo issues cost 2.1 (`cuCtxSetCurrent`) to 7.2 us (`cblas_dgemm`) through FFM.

What it is worth: a `--gpu` member with its result read back is 63 us in the binary against 30 on
the JVM, ~19 of the gap being the member's 5.65 driver calls' interpretation; a decode forward's
~1300 driver calls (`.kb/gpu.md`, "What the decode step waits on") are ~5 ms. `--blas` in the binary
now declines below 2^15 / 2^17 instead of 64 because of it, and would go back to the JVM's crossover
with the floor gone.

## Why it is not a small change

- The API is a MODULE of the GraalVM JDK (`--add-modules org.graalvm.nativeimage`), absent on any
  other JDK, and the core libraries import nothing. It has to be a native-profile SOURCE SET the way
  `src/web/java` substitutes classes under `-Pweb`: the binding halves of `am.ik.gpu.CudaDriver`,
  `eval/LinalgBlasKernels` and `am.ik.objc`'s runtime, replaced under `-Pnative`, with the FFM
  versions staying what `java -jar` and every compiled `.class` run.
- One `@InvokeCFunctionPointer` interface method per SHAPE: 45 CUDA shapes, 6 BLAS, the objc table.
  Word-typed arguments cannot cross a lambda; a `MemorySegment` becomes a raw address.
- `critical(true)` heap access has no equivalent: an array operand must be pinned
  (`PinnedObject`) for the call or staged through native memory. The BLAS gemm/gemv pass heap
  arrays; the CUDA copies stage through pinned host buffers already.
- The reachability metadata (`foreign.downcalls`) stops mattering for whatever moves; the tests
  that pin it (`NativeImageForeignConfigTest`, `LinalgBlasDeclineTest`,
  `ObjcNativeImageForeignConfigTest`) need a native-profile answer.

## What to do

1. Prototype ONE shape end to end under `-Pnative` (`cuCtxSetCurrent`, or the BLAS gemv) with the
   profile's source set, and measure the binary's `vec:matvec` crossover again
   (`.todo/artefacts/727-.../crossover.lisp`). If the crossover returns to the JVM's, the route is
   real.
2. Decide the shape of the substitution before widening it: a per-shape interface generated from
   the existing `FunctionDescriptor` table would keep the 45 CUDA entries in one place.
3. If it lands, `LinalgBlasKernels.minWork`'s native pair goes back to 64 with the measurement that
   says so, and `.kb/gpu.md` / `.kb/linalg-blas.md` / `.kb/objc.md` lose their "interpreted" sentences.

## Acceptance

The binary's per-call floor for the shapes it issues is within 2x of the JVM's, measured by
`ShapeFloor.java`'s method on the built binary, and the `--blas` thresholds are one number again.
