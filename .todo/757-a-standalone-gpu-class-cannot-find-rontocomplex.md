# 757. A `--gpu` class run standalone dies with `NoClassDefFoundError: am/ik/rontolisp/runtime/RontoComplex`

Difficulty: Medium

Filed 2026-09-10 by `.todo/729`'s certification run on GB10. Rule 4 of `.kb/lanes-and-certification.md`: the test is
`@EnabledIf("aDeviceIsAvailable")`, so only the device lane can see it, and it is red there.

## The red

`JvmLinalgGpuAccelCompilerTest.aLazyResultAllocatesNoHostArrayOnTheCompiledBackend` (`0137b134d`,
`.todo/492`) writes the compiled `Test.class` ALONE into a directory and runs it in a 256 MB JVM
with `-cp <dir>`:

```
Exception in thread "main" java.lang.NoClassDefFoundError: am/ik/rontolisp/runtime/RontoComplex
	at Test.LINALG$colon$colon$pctLA-SPLIT-ELEMENT-TYPE(Unknown Source)
```

Reproduced on `d0daa93f0` with no local change (stash-and-rerun), so it is develop's, not 729's.
Every OTHER test in the class passes, including the ones that run the same kind of program
through `run(...)`: the difference is the classpath. `run` has the test classpath, where
`RontoComplex` is on `target/classes`; the small JVM has only what the emitted class carries.

## What it is

The complex runtime is GATED (`JvmComplexRuntimeBuilder`, `.kb/jvm-complex.md`): the `_c*`
helpers and the `RontoComplex.class` file travel only "when the program may create a complex".
The spliced `linalg.lisp` function `%la-split-element-type`
(`src/main/resources/am/ik/rontolisp/eval/linalg.lisp`, `linalg:arange`'s keyword splitter)
references the class anyway, through `(numberp (car a))`: a complex IS a number, so the
backend's `numberp` test now links `RontoComplex` in its `instanceof` chain whether or not the
gate ("may create a complex") opened, and a program that never creates one still LINKS to the
class -- a `.class` output that is meant to stand alone (`.kb/jvm-export.md`, "What travels")
does not. The `--gpu` flag is incidental: it splices `linalg.lisp` into this program and the
program calls `linalg:arange` with `:element-type`. Expect the same from ANY standalone class
whose program says `numberp` (or `arange`), which is why the test in step 1 must not need a
device.

## What to do

1. A failing test first (global rule): a JVM-only pin that compiles a program reaching
   `%la-split-element-type` WITHOUT `--gpu`, writes the class alone and runs it in a subprocess --
   so the red is visible from the x64 lane and not only behind the device gate.
2. Either widen the gate to "the program REFERENCES a complex type" (an `instanceof`
   `RontoComplex` is a reference), or compile the type test in a way that does not link the
   class when the gate is closed. The gate's byte-identity promise for complex-free programs
   (`.kb/jvm-complex.md`) decides which.

## Acceptance

`aLazyResultAllocatesNoHostArrayOnTheCompiledBackend` green on GB10; the new pin green on both
boxes; `JvmRuntimeClassFilesTest` unchanged.
