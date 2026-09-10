# 756. `(exp 1)` prints `2.7182818284590455` on aarch64 through the JVM backend; the test expects the x64 digits

Difficulty: Low

Filed 2026-09-10 by `.todo/729`'s certification run on GB10 (aarch64, GraalVM 25.0.4). Rule 4 of
`.kb/lanes-and-certification.md`: this red is invisible from the x64 lane, which certifies `JvmLispCompilerTest` green.

## The red

`JvmLispCompilerTest.compileAndRunComplexExptExpLogTrig` (added by `30eb54724`, `.todo/752`):

```
expected: "2.718281828459045"
 but was: "2.7182818284590455"
```

at `(print (exp 1))`. Reproduced on `d0daa93f0` with no local change (the stash-and-rerun in
`.todo/artefacts/729-.../`'s session), so it is develop's, not 729's. The other seven assertions in
the method pass, including `(exp #c(0 1))`.

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
