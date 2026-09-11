# Give the interpreter the same stack on every platform

Difficulty: Medium

## What was measured

`java -jar rontolisp.jar prog.lisp` runs the interpreter on the launcher's `main`
thread, whose stack is the JVM default: about 8 MiB on macOS, **1 MiB on
linux-x64**. The interpreter's recursion depth is the program's, so the depth
ceiling of a recursive Lisp program is a different number on each platform, and
the Linux one is small.

Measured 2026-09-11 (aarch64, `java -Xss<N> -jar ... --system-path
src/test/resources/cl-mustache mustache-spec.lisp`, the cl-mustache spec suite
through `asdf:load-system`):

| `-Xss` | result |
| ------ | ------ |
| 768k   | `StackOverflowError` |
| 832k   | 194 cases, `pass=158 fail=36` |

So an ordinary vendored library's own test suite sits inside Linux's default
margin. CI hit it: `ClMustacheSpecE2eTest.loadsAndRunsOnTheInterpreter »
StackOverflow` on run 34580774640, green on every local box. The in-process E2E
leg now runs on a 16 MiB thread (`AsdfLibraryE2eSupport`, `.kb/test-execution.md`)
-- but that fixed the HARNESS, not the product: a user running the same program
through `java -jar` on Linux still gets 1 MiB.

## The change to consider

`RontoLispCli.main` already hands the CLI to a 16 MiB-stack thread
(`WORKER_STACK_BYTES`) when macOS/AppKit demands thread 0
(`ObjcInterop.mainThreadHandOverRequired()`, `.kb/objc.md`). Make that hand-over
unconditional -- run `launch(args)` on the worker and join it everywhere else,
parking thread 0 only where the run loop is needed -- so the depth ceiling is one
number on every platform and every launcher.

Open questions to answer before landing it:

- the native binary (GraalVM) and the browser build (`src/web/java`, which never
  reaches `cli`): does the worker start early enough there, and does anything
  read `Thread.currentThread()` identity on the way?
- `System.exit` vs returning the code: today the non-objc arm returns through
  `exit(launch(args))`; a joined worker has to carry the code back without
  swallowing an `Error`.
- signal handling and the REPL's stdin path on a non-main thread.
- whether the ceiling should be a flag (`--stack <MiB>`) rather than a constant,
  since a deeply recursive program can always want more.

## Verification

- the table above, re-measured on the binary and on `java -jar` (Linux and
  macOS): the threshold should stop moving with the launcher.
- `./mvnw test`, the GUI hand-over check in CLAUDE.md's "After Task Completion"
  (the objc arm is the one this touches), and the native E2E leg.
