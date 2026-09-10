# 730. Report the two SubstrateVM downcall findings upstream (oracle/graal)

Difficulty: Low

Filed 2026-09-07 by `.todo/727`. Both reproducers are minimal, standalone and checked in under
`.todo/artefacts/727-an-ffm-downcall-costs-2-1-us-inside-a-native-image/`; the report is written
below, and posting it is a public action for a person to take.

## 1. A downcall handle created at build time crashes the image build (bug)

`BuildTimeHandle.java`: a holder class initialised under `--initialize-at-build-time` holding
`Linker.nativeLinker().downcallHandle(FunctionDescriptor)` (address-first, no native pointer in the
heap). GraalVM 25.0.4 native-image fails in analysis with
`VMError$HostedError: should not reach here: unexpected input could not be handled: linkToNative` at
`PolymorphicSignatureWrapperMethod.buildGraph(PolymorphicSignatureWrapperMethod.java:170)`. Expected:
either the handle is compiled like any other constant method handle, or it falls back to the
interpreter -- not a crash. Log excerpt: `build-time-handle.error.txt`.

## 2. A run-time downcall handle costs ~1.7 us + ~0.4 us per argument (performance)

`DowncallFloor.java` / `ShapeFloor.java`: `Target_java_lang_invoke_LambdaForm.forceInterpretation()`
returns `true`, so every handle created at run time is interpreted (`LambdaForm.interpretName`,
reflection accessors, boxed arguments); a `cuLaunchKernel`-shaped call is 6.2 us against 17 ns on
the JVM and 10.7 ns through `@InvokeCFunctionPointer` in the same image. Ask whether a downcall handle
-- whose stub already exists at build time from `reachability-metadata.json` -- could get an AOT
invoker keyed on the registered shape, since the shape set is closed by construction. (Since
`.todo/729`, 2026-09-10, this project works around it for its CBLAS calls with a `-Pnative`
substitution issuing `@InvokeCFunctionPointer` calls on the addresses the FFM lookup found --
`.kb/native-downcalls.md`; ~90 ns a call with three `PinnedObject`s against 6.4-7.2 us through the
handle. Worth saying in the report: the workaround needs a hand-written interface per shape, which
the registered-shape table already knows.)

## What to do

Open the two issues on github.com/oracle/graal with the files above (the `perf` table and the
per-shape table are in the artefact README), and record the issue numbers in `.kb/gpu.md` next to
"An FFM downcall inside a native image costs".
