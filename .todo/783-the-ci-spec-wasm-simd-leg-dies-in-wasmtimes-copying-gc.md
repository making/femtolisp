# 783. The ci-spec WASM `--simd` leg dies inside wasmtime's copying GC

Difficulty: Medium

`CiSpecE2eTest`'s `run-wasm-simd` leg fails on `linux/amd64` with wasmtime 47.0.3:

```
command [wasmtime, --wasm, gc, --wasm, exceptions=y, --dir, ., --dir, /tmp,
         test-simd.wasm, alpha, beta] exited with 1
  2: BUG: there should always be enough room in the active semi-space for objects that
     survived collection, since the active space is the same size as the idle space
location: crates/wasmtime/src/runtime/vm/gc/enabled/copying.rs:540
version: 47.0.3
```

Measured 2026-09-11 on the native-binary run
(`./mvnw -Pnative clean package -DskipTests` then
`./mvnw -Dtest=CiSpecE2eTest -Drontolisp.binary="$PWD/target/rontolisp" test`):
**one** failure out of 3814, `e2e()[6][1]`, reproduced on two consecutive runs, at
11.4 s into the leg. Everything else is green -- the interpreter, interpreter `--simd`,
JVM, JVM `--simd` and the plain **WASM** legs all pass, the last in 52.6 s over the same
program. So the module runs; only the `--simd` lowering of it exhausts wasmtime's
copying collector, and wasmtime itself calls the state a bug of its own ("This is a bug
in Wasmtime that was not thought to be reachable").

Not covered by `./mvnw test`: `CiSpecE2eTest` runs **zero** tests without
`-Drontolisp.binary`, so a full suite says nothing about this leg -- which is also why
the item cannot say when it started failing.

## What to do

1. Establish whether it predates the SIMD lowering's current shape: run the same leg on
   an older commit's binary, and on the JVM-compiled `test-simd.wasm` (the module is
   built by whatever `--binary` names, so the compiler is a variable too).
2. Establish whether it is wasmtime's version: 46 and 48 on the same module. `.kb/fetch-http.md`
   already pins host versions for the component work; a GC-heap regression between
   releases belongs beside them.
3. If the module is at fault rather than the engine, the question is which `--simd`
   emission allocates per iteration where the scalar path does not -- the leg dies
   11 s in, so it is a steady-state allocation rate, not a single huge object.
4. Whatever the answer, this leg needs to be REACHABLE from a normal run or it will rot
   again: either a size-bounded `--simd` case in `ci-spec.yaml` that the ordinary suite
   runs, or a CI job that runs the binary legs.

## Related

- `[[779-complex-division-is-the-naive-formula-not-smiths]]` -- where this was measured
  (its change is in `_c_div`, reached identically by both WASM legs, and the plain WASM
  leg passes)
