# 756. `(exp 1)` prints `2.7182818284590455` on aarch64 through the JVM backend; the test expects the x64 digits

Difficulty: Low

Filed 2026-09-10 by `.todo/729`'s certification run on GB10 (aarch64, GraalVM 25.0.4). The x64 box runs
the full suite and only the aarch64 box runs the device legs, so this red is invisible from the
x64 lane, which certifies `JvmLispCompilerTest` green.

## The red

`JvmLispCompilerTest.compileAndRunComplexExptExpLogTrig` (added by `30eb54724`, `.todo/752`):

```
expected: "2.718281828459045"
 but was: "2.7182818284590455"
```

at `(print (exp 1))`. Reproduced on `d0daa93f0` with no local change (the stash-and-rerun in
`.todo/artefacts/729-.../`'s session), so it is develop's, not 729's. The other seven assertions in
the method pass, including `(exp #c(0 1))`.

## Measured 2026-09-10 on darwin/aarch64 (M-series, JDK 25): the interpreter agrees with the backend

```
(print (exp 1))    interpreter: 2.7182818284590455    JVM backend: 2.7182818284590455
(print (exp 1d0))  interpreter: 2.7182818284590455    JVM backend: 2.7182818284590455
```

So this is the SECOND of the two branches below, not the first: the backend is not reaching a
different `exp` from the interpreter, and there is nothing to make them agree -- they already
do, on this platform and presumably on GB10. What is platform-specific is the LITERAL at
`JvmLispCompilerTest.java:7209`, taken on x64. The fix is therefore the assertion's shape, not
the backend: assert what the interpreter prints for the same source (the mirror this test
class exists to be) rather than a string, or assert a `StrictMath`-anchored form that no
platform can move.

The measurement also means `./mvnw test` is red on any aarch64 box today, before any local
change -- worth knowing before reading a failure in this class as one's own.

## What it probably is

`Math.exp` is allowed 1 ulp and is an intrinsic per platform; `StrictMath.exp(1.0)` is
`2.718281828459045` everywhere. Either the JVM backend's real `exp` (or the complex path's
`_cexp` falling back for a real argument, `.kb/jvm-complex.md`) reaches `Math.exp` where the
interpreter reaches `StrictMath` -- or both reach `Math.exp` and the expectation was taken on
x64. Check which, then pin what the interpreter prints on BOTH boxes: if the interpreter says
`2.718281828459045` on aarch64 too, the backend must match it (`StrictMath`); if the interpreter
also says `...455` there, the test's literal is the platform's and the assertion wants a
platform-free form.

## Acceptance

`./mvnw -Dtest=JvmLispCompilerTest#compileAndRunComplexExptExpLogTrig test` green on GB10 and on
dorian, and the four backends agree on `(exp 1)` on both (`.kb/running-backends.md`).

## Related

- `[[765-jvm-complex-acos-tan-and-tanh-answer-wrong-values]]` -- a different defect (wrong
  values, not a last digit) in the same `_cu1` family, and it rewrites this very test method to
  pin the JVM against the interpreter instead of against literals. That is the shape this item
  wants, so whichever lands first should do the rework for both.
