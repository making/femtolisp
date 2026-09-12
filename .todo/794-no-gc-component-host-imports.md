# `--no-gc --component` takes no host imports

**Status:** open. Filed 2026-09-12, alongside
[`792`](792-no-gc-host-imports.md)'s landing.

Difficulty: High

## The finding

`792` gave the `--no-gc` CORE module `rontolisp:wasm-import` (and, through it,
`rontolisp:wit-import`): the measurement reactor went from "compile it on the GC backend"
to 528 bytes, against 1,658 for the same source on wasm-GC. The COMPONENT wrap did not
follow, and refuses the directive by name:

```
rontolisp:wasm-import 'X' is not supported with --no-gc --component: a component's imports
are component-model imports lowered through the canonical ABI, which the core-module wrap
does not build. Compile the reactor form instead ...
```

That is honest but it is a hole. `NoGcWasmComponentBuilder` is a pure POST stage over the
finished core module, and its whole shape today is "no import block, no adapter, no mem
module" (`.kb/no-gc-scalar-wasm.md`, `--no-gc --component`). A component that imports
needs the other half of what the wasm-GC path has in `WasmComponentImportCompiler`: an
import block, one `canon lower` per imported function, and the canonical ABI for a
`:string` argument or result -- which the core module already half has, since `--no-gc
--component` grew `cabi_realloc` and the retptr shims for its `:string` EXPORTS.

## Why it is not obviously worth doing

Measure before building. The three questions, in order:

1. **What does it cost?** A scalar-only import needs the import block, a lower per
   function and nothing else; a `:string` import needs the lift/lower pair over the
   module's own memory. Compare against the core-module + hand-written-host route, which
   already works and is what every host outside the component world uses.
2. **Who asks for it?** A component's selling point is that its host needs no glue. A
   `--no-gc` module's host glue is already `new WebAssembly.Instance(module, {env})` with
   flat scalars and `(ptr,len)` -- so the component wrap buys less here than it does on
   wasm-GC. The case that does want it is a WIT world with BOTH imports and exports
   targeting a component host (wasmtime `--invoke`, jco), where today the answer is the GC
   backend at ~110 KB.
3. **Does the print micro-adapter compose?** A printing `--no-gc --component` already
   wires three fixed core modules and lifts every export ASYNC. Adding user imports on top
   of the shim/fixup pattern is where the real difficulty is; a print-free program is the
   easy half and is probably where this should start (or stop).

## Touch points

- `codegen/wasm/NoGcWasmComponentBuilder.java` (the wrap; today it builds no import block)
- `codegen/wasm/WasmComponentImportCompiler.java` (the wasm-GC canon-lower side, to read
  rather than to copy -- its value model is not this one's)
- `codegen/wasm/NoGcWasmCompiler.java` (`validateImport`, which refuses today)
- `compiler/WitImportDirective.java` (the `--no-gc` branch lowers to `wasm-import`; a
  component build would have to take the `%component-import` branch instead)
- `.kb/no-gc-scalar-wasm.md`, `.kb/wit.md`
