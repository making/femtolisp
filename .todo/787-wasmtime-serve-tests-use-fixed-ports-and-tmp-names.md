# 787. `wasmtime serve` tests in WasmLispCompilerIntegrationTest use fixed ports and /tmp names

Difficulty: Medium

Follow-up to `[[781-wasm-test-scratch-dirs-collide-across-worktrees]]` (now closed): that
item fixed `workDir()`, `stageAbsolutePathTree()` and `HostWasmtime.ROOT` to be unique per
PID as well as per thread, which was the reported bug (39/65 unrelated failures from two
concurrent `./mvnw test` runs sharing `/tmp/rontolisp-wasmtime/w<threadId>`). Verifying that
fix by running two `WasmLispCompilerIntegrationTest` processes concurrently (JUnit Platform
Launcher against the built `target/classes` + `target/test-classes`, same technique 781
used) turned up a SEPARATE, still-live collision family that 781 could not touch: 781's
scope was restricted to non-test-method helpers, because another session was concurrently
touching this file's test methods for `.todo/785`.

## Measured 2026-09-12

After 781's fix, two concurrent runs of `WasmLispCompilerIntegrationTest` (JUnit Platform
Launcher, both processes `cd`'d to the same worktree, run from this same tree) reported 2
and 7 failures respectively -- zero of them from any `workDir()`-based test (the ~1550
tests that make up the bulk of the class), all of them from the `wasmtime serve` family:

```
httpHandlerRandomClockAndPrintUnderWasmtimeServe: Address already in use (os error 98)
httpHandlerFetchInsideServeUnderWasmtimeServe: Address already in use (os error 98)
componentTlsUpgradeAttemptsARealHandshakeAndRejectsAnUntrustedServer: wasm trap: cast failure (SIGABRT, exit 134)
```

A solo run of the same class is green (confirmed immediately before and after this
measurement: 1556 tests, 0 failures, 5 skipped).

`grep -n '\-\-addr 127.0.0.1:[0-9]*'` finds 17 `wasmtime serve` invocations across this
class (lines ~5240-22328 as of this writing), each with its own hardcoded port
(8081-8096) and its own hardcoded `/tmp/serve-*.wasm` / `/tmp/*.log` name. Each name is
unique WITHIN the file, so nothing collides inside one JVM's concurrent run -- exactly the
same shape as 781's bug: a shared CONSTANT (here, a port number and a `/tmp` literal
instead of a directory name) that two independent JVMs on the same machine both bind and
both start `wasmtime serve` against, corrupting or hanging both.

## What to do

1. Give each `wasmtime serve` test a port and a `/tmp` (or workDir-scoped) name that is
   unique per PID (and per thread, since these run `@Execution(CONCURRENT)` too) -- the
   same `ProcessHandle.current().pid()` device 781 used, or an ephemeral port obtained by
   binding a `ServerSocket(0)` and closing it just before `wasmtime serve` binds it (racy
   but standard practice; document the race if used).
2. This DOES require editing existing `@Test` methods in
   `WasmLispCompilerIntegrationTest` (the port and the `/tmp/serve-*` literals are inline
   in each method's command string) -- coordinate with whatever else is touching this file
   at the time (`.todo/785` was concurrent when 781 landed; check `.todo/` for anything
   live before starting).
3. Reproduce first the way 781 did: run two `WasmLispCompilerIntegrationTest` processes
   concurrently (a compiled JUnit Platform Launcher invocation against `target/classes` +
   `target/test-classes` avoids the "two maven runs in one worktree corrupt target/" trap
   from `CLAUDE.md`) and confirm the `Address already in use` / random cast-failure
   pattern before the change, gone after.

## Related

- `[[781-wasm-test-scratch-dirs-collide-across-worktrees]]` -- where this was measured
  and the sibling bug (scratch directories) was fixed
