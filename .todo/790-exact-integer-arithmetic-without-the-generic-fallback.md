# Fixnum arithmetic drags the whole numeric tower in: 1,960 bytes for `fib`

**Status:** open. Measured 2026-09-12.

Difficulty: High

The arithmetic half of the measurement in
[`789`](789-string-boundary-drags-in-the-charvec-normalizer.md) -- read its table first;
the program and the flags are there and are not repeated here.

## The finding

```lisp
(defun fib (n) (if (<= n 1) n (+ (fib (- n 1)) (fib (- n 2)))))
(rontolisp:wasm-export 'fib :as "RunComputation" :params '(:s32) :returns :s32)
```

`--no-wasi --optimize=size` = **2,281 bytes**; the same module with one `(defun noop () nil)`
in place of `fib` is **321**. So three operators on a value that entered as an `:s32` and
never leaves the i31 range cost **1,960 bytes** -- `fib`'s own body is 54 of them.

What those bytes are, read out of the module:

| Bytes | What |
| ---: | --- |
| 165 | `_rat_new` (with its GCD loop), `_rat_num`, `_rat_den` |
| 284 | generic `+`, `-` (`_rat_add`/`_rat_sub`: float contagion, then exact-int, then the rational path) |
| 196 | `_rat_cmp`, `_rat_cmp_bits` |
| 255 | `_to_f64`, and the float-to-decimal helpers it roots |
| 644 | the bignum/limb tier: `_big_add`/`_big_sub`, limb get/normalize/compare, `_big_to_f64` |
| ~400 | `_int_new`/`_int_val` and the i64<->i31<->bignum plumbing between the above |

**`--optimize` (speed) is bigger, not smaller**: 2,411 for this program, 4,763 for the full
one. Integer fusion is off under `--optimize=size`
(`WasmIntFusionCompiler.speedTradesEnabled`), and when it is on it adds the unboxed fast
path WITHOUT removing anything, because `.kb/wasm-int-fusion.md`'s invariant is that the
fast path has a **total fallback** -- the fallback is what keeps every function in the table
above reachable.

**The prize.** A spike replacing `_rat_add`/`_rat_sub`/`_rat_cmp_bits` with i31-only bodies
(`ref.cast (ref i31)`, the i32 op, `ref.i31`) and the `:s32` export return with
`castI31GetS` instead of the f64 normalization takes this program to **639 bytes** and the
full one to **1,030** (816 after `wasm-opt -Oz`), still correct on the Node host. The spike
is not the fix -- it traps on a float -- it measures what the fix is worth.

## The shape of a sound version

Three mechanisms, increasing in reach and cost. They compose; each is worth landing alone.

**1. Representation inhabitance, as an optimistic fixpoint on the FINISHED module.**
A branch guarded by `ref.test (ref T)` is dead when no value of type `T` can exist in this
module. Start by assuming every struct/array type uninhabited, fold every `ref.test` of an
uninhabited type to `i32.const 0`, drop the branches that die, then look at which
`struct.new`/`array.new` sites survive; repeat to a fixed point. Seeds are the types the
PROGRAM's own code and the boundary can construct -- a `:float` parameter seeds
`TYPE_FLOAT`, an `:s-expr` seeds whatever the reader builds.

- It is sound only because the module is closed: a wasm-GC struct cannot be handed in by a
  host, so the only constructors are the ones in the module. Write that down where the pass
  lives -- it is the pass's whole licence.
- It runs on bytes, after `WasmTreeShaker.shake`, so no runtime builder has to learn about
  it, and it generalizes past arithmetic: it is what would kill the float branches, the
  limb tier, and `789`'s charvec renderer without a source-level scan.
- It belongs in `am.ik.wasm` (language-independent) next to the shaker, not in
  `codegen.wasm`.
- **What it does NOT kill here**: the rational path in `_rat_add` is the `else` of "both
  operands exact-int", not a `ref.test (ref TYPE_RATIO)` branch, so inhabitance alone
  leaves it. That is mechanism 2's job.

**2. Call-site operand typing, so a proved-exact call site needs no fallback.**
Fusion already classifies leaves; what it does not do is conclude that a whole call is
exact. When every leaf of a tree is proved to be an exact integer -- a boundary `:s32`
parameter, a literal, another proved-exact defun's return -- the generic fallback is
unreachable BY THE PROOF, and the site can emit the i64 fast path with nothing behind it
but the overflow promotion. `fib` is exactly this case: `n` enters from an `:s32` export
(i31), and `(- n 1)` and `(+ ...)` keep it exact.

- This needs a return-type fixpoint over the call graph. `--no-gc`'s `inferTypes` is one
  already (monotone, INT widening to FLOAT, exported params pinned to the boundary
  designator) -- the wasm-GC backend wants the same lattice with more points (exact / float
  / rational / other), and [`792`](792-no-gc-host-imports.md) is the argument for sharing
  one implementation rather than writing a second.
- Watch the interaction with `--optimize=size`: the point of this mechanism is that it
  makes the module SMALLER, so unlike fusion it must be on at every optimize level.
- The trap-vs-fallback question is settled by the proof: where the proof holds there is no
  behaviour to fall back to. Where it does not hold, nothing changes.

**3. The `:s32` boundary should not normalize through f64.**
`WasmExportCompiler.emitUnboxResult` sends every integer result through
`castFloatGetF64` + a trapping `i32.trunc`, deliberately, so that a float or ratio return
crosses. That single call is what roots `_to_f64` and the float-to-decimal helpers in a
module whose exports are all integer. With mechanism 1 or 2 the f64 path folds away by
itself; without either, an inline i31 fast path in front of the existing call would cost a
few bytes and save the rest -- but only once something else stops the generic `+` from
rooting `_to_f64` anyway, so do not land this one first and call it a win.

## Touch points

- `am/ik/wasm/` -- the new pass, beside `WasmTreeShaker`
- `codegen/wasm/WasmIntFusionCompiler.java`, `WasmComparisonCompiler.java`
- `codegen/wasm/WasmRatioRuntimeBuilder.java` (`buildRatBinaryBody`, `buildRatCmpBitsBody`)
- `codegen/wasm/WasmExportCompiler.java` (`emitUnboxResult`)
- `codegen/wasm/NoGcWasmCompiler.java` (`inferTypes`, the lattice to share)
- `.kb/wasm-int-fusion.md` (the total-fallback invariant gains an exception with a proof
  obligation), `.kb/wasm-bignum.md`, `.kb/optimize-dead-code-elimination.md`
