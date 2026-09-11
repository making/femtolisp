# 781. The WASM test scratch directory is shared by every worktree on the machine

Difficulty: Low

`WasmLispCompilerIntegrationTest.workDir()` stages every module and every guest-visible
data file under

```
System.getProperty("java.io.tmpdir") + "/rontolisp-wasmtime/w" + Thread.currentThread().threadId()
```

and `HostWasmtime.ROOT` is the same `/tmp/rontolisp-wasmtime`. The thread id is unique
inside ONE surefire fork. It is not unique across JVMs, and the path has nothing else in
it -- no PID, no worktree, no random suffix. Two `./mvnw test` runs on the same machine
(the normal state of this repo: several sessions, one worktree each) therefore share the
directory pair for pair: both wipe it on first use, both write `test.wasm` into it, and
each one's `wasmtime` runs whichever module landed last.

Measured 2026-09-11 while `.todo/779` was landing: a full suite in one worktree, run
alongside another session's full suite in a second worktree, reported **39 failures in
`WasmLispCompilerIntegrationTest` and nowhere else** -- `compileVectorLiteralPrintsAsHashParen`
answering `(1 2 3)` for `#(1 2 3)` (a DIFFERENT program's output), `writeByteReturnsByte`
exiting non-zero with empty stderr, SIMD-vs-scalar differentials disagreeing, file-IO
cases failing on files another run had deleted. The same class, re-run alone immediately
afterwards over the identical tree, was green (`exit=0`, zero failures).

This is worse than a flake: it is 39 red tests that point at real features and at nothing
that is wrong, and the reflex it invites is to "fix" the feature. `CLAUDE.md` names the
sibling trap ("Two maven runs in one worktree corrupt `target/`") but tells sessions to
work in separate worktrees, which this defeats.

## What to do

1. Make the scratch root unique per JVM as well as per thread -- the process handle
   (`ProcessHandle.current().pid()`) or `Files.createTempDirectory` under
   `rontolisp-wasmtime`, with the thread id kept underneath it so the `--dir .` isolation
   the current comment describes still holds.
2. `HostWasmtime.ROOT` and anything else naming a fixed `/tmp/rontolisp-*` path (grep the
   test tree: the other WASM classes, the CLI E2Es and the native E2E stage files too)
   goes the same way. A shared CONSTANT path is the bug, not this one directory.
3. Keep the stale-file wipe: a per-run directory still outlives a killed JVM, so leave
   the recursive clean (or delete the run's root on exit).
4. Reproduce first: two `./mvnw -Dtest=WasmLispCompilerIntegrationTest test` runs started
   together in two worktrees should fail before the change and both pass after it.

## Related

- `[[779-complex-division-is-the-naive-formula-not-smiths]]` -- where this was measured
