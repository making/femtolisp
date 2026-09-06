# 720. The JVM and wasm-GC `--simd` compiled outputs still signal on a `vec:` mixed-width pair

Difficulty: Medium

Found 2026-09-06 closing `.todo/686`, which fixed the SAME bug in the interpreter's
`--simd` (`eval/VecSimd`) only: a mixed `#f`/`#d` (or a non-fused bf16 pairing) `vec:`
call is not an error -- `vec.lisp`'s `%map2` and friends read every operand through
`aref`, which widens whatever the packed storage width is, so the scalar defun computes
it happily. `--simd` must be a speed flag, never a correctness one (`.kb/vec.md`, "The
four acceleration layers").

686 closed that hole for layer 0 (the interpreter) by making every width-mismatch arm in
`VecSimd` answer `null` -- decline -- instead of throwing, so the scalar defun binding it
captured runs. It did not touch:

- **Layer 1, the JVM compiler's `--simd`**: `JvmSimdVectorTemplate` still has ~13
  `throw mixedWidth();` sites (grep the class), reachable from the embedded
  `RontoLispSimdBridge` a compiled `-o Prog.class --simd` program calls through a bare
  `INVOKESTATIC`. There is no decline path back to the scalar `vec.lisp` bytecode the way
  `VecSimd.defineFn` hands back to the captured interpreter closure -- that is precisely
  why 686 left this layer alone rather than folding it in as "small."
- **Layer 3, wasm-GC's `--simd`**: `.kb/vec.md` documents `requireSameKind` trapping a
  mixed-width call in the hand-written v128 runtime helpers (`WasmVecSimdRuntimeBuilder`).
  Same shape: a static lane-loop function with no fallback to the scalar path.
- Layer 2 (`--no-gc`) is a different animal -- it replaces vec: wholesale and is typed at
  compile time, so a mixed-width call there is presumably a COMPILE-time error already,
  not a runtime one a program can trigger by surprise. Confirm this rather than assuming
  it, since it changes whether this item has three fronts or two.

Whether this is actually reachable by ordinary Lisp source (not just by constructing
mismatched arrays through interop) and how much it costs to run is unmeasured -- see
`.todo/694`, which is about the E2E corpus having no `--simd` axis at all, so a regression
here would not currently turn any case red on any backend.

## Do

1. Measure first: write a `vec:add`/`vec:dot`/`vec:matvec` mixed-width program, compile it
   `-o Prog.class --simd` and `-o Prog.wasm --simd`, run both, and confirm today's
   `RuntimeException`/trap is real and not already caught by a static check earlier in the
   pipeline (`CompileFrontend`, a type inference pass, etc).
2. If real: a compiled call site cannot "hand back" to a closure the way the interpreter
   does, since there is no defun object at runtime, only emitted bytecode/wasm. The likely
   shape per backend is a runtime type check ahead of the lane path that falls through to
   an emitted scalar loop (the same shape the scalar `vec.lisp` defun's bytecode already
   is) rather than raising -- which is a real codegen change, not a rename, hence Medium
   rather than Low.
3. Update `.kb/vec.md`'s "four acceleration layers" section (already touched by 686) once
   this is closed, so it no longer says layers 1/3 diverge from layer 0 on this shape.

## Verify

- The JVM and wasm outputs of a mixed-width `vec:` call match their own scalar (non-simd)
  compile, the same "bit-identical to the flag-off oracle" rule 686 pinned for the
  interpreter -- not necessarily identical to EACH OTHER byte-for-byte, per backend's own
  contract.
- `.todo/694`, if still open, is the right place to add the regression case once there is
  an axis to add it to; do not invent a parallel one-off harness for this if that item's
  mechanism is close to landing.

## Not in scope

`--no-gc` unless step 1's measurement shows it has the same runtime hole; the write-up
above expects it does not.
