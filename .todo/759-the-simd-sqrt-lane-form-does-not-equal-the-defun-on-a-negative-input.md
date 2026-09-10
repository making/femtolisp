# 759. `--simd`'s `sqrt` lane form does not equal the defun on a negative input

Difficulty: Medium

Found 2026-09-10 while reading `.todo/747`'s close (the element-wise `bfloat16` `vec:`
kernels). 747 recorded it as a passing remark inside `.kb/bfloat16.md` because that is where
it surfaced; it is **not a `bfloat16` fact and not 747's defect** -- the f32 lane has carried
it since lane forms existed -- so it is filed here rather than left in that file's bf16
section, where nothing looking for it would find it (rule 9: a restated fact decays where no
child owns it).

## The divergence

`.kb/vec.md` states the rule under which a member is allowed a lane form at all:

> **Lane forms only where they equal the defun.** Interpreter/JVM and wasm-GC lane-ize sqrt,
> abs, negative and reciprocal only (`VectorOperators.EXP` is not bit-identical to
> `Math.exp`; gate `JvmSimdVectorTemplate.hasLaneForm`)

`sqrt` is on that list, and on the non-negative half it belongs there. On the negative half
the two paths are not the same function:

- the defun is `(defun vec:sqrt (v) (vec::%map1 #'sqrt v))` (`vec.lisp:161`, `-into` at 265),
  and **CL `sqrt` is complex-extended** -- `(sqrt -1.0)` is a complex, which a
  `single-float` / `bfloat16` element store cannot hold;
- the lane form is the hardware square root, which answers NaN and stores it without
  complaint.

So the same expression is expected to answer differently with `--simd` on and off. **The
first move is to CONFIRM that, per element type and per backend, and write down what each
of the two paths actually does** -- the sentence above is read off the source, not measured,
and "the defun signals" is the half most likely to be wrong (the error may come from the
complex constructor, from the store, or the value may be silently coerced).

## Why no suite has caught it

Every sweep that could have sits on the non-negative side of the condition, deliberately and
with a comment:

- `VecSimdTest:452-456` sweeps the unary members over a range that includes negatives and
  skips exactly one: `if (!op.equals("sqrt"))`.
- `VecSimdTest:802-823` (747's new bf16 sweep) runs `sqrt` as `(vec:sqrt (vec:abs vb))` and
  says why in a comment: "sqrt runs over the non-negative half: CL sqrt is complex-extended".
- `ci-spec.yaml`'s `vec:sqrt` cases (6151) are non-negative, as are the model programs, which
  reach `sqrt` only through a norm.

This is standing rule 6 in `.todo/670` in its purest form -- a suite holding a defect
invisibly while every case sits on one side of its condition -- and the *more* exhaustive
sweep (747's, all 65536 patterns) is the one that had to exclude it explicitly.

## What to decide

The item is a semantics decision first and four edits second. `(vec:sqrt #f(-1.0))` has to
answer ONE thing, on the interpreter, the JVM class, wasm-GC and `--no-gc`, with `--simd` on
and off. The candidates, and none is obviously right:

1. **NaN on both paths** -- make the defun's element function the float-domain `sqrt`, not
   CL's. Cheapest, matches every other array language, and breaks CL conformance for the
   `vec:` layer only (which is already not CL -- `vec:` is rontolisp's own).
2. **Signal on both paths** -- take the lane form off the `hasLaneForm` gate for `sqrt` (the
   same treatment `exp` gets, for the same stated reason) and let the defun's error stand.
   Honest and consistent with `.kb/vec.md` as written, but it means `vec:sqrt` loses its
   kernel entirely, including on the non-negative data every real program passes it.
3. **Keep the lane form and narrow the CONTRACT** -- `vec:sqrt` is defined on non-negative
   input, negative is undefined, and `.kb/vec.md` says so. Costs nothing and is the least
   defensible: an undefined-behaviour clause that exists to protect an optimisation is the
   thing rule 6 is about.

A fourth exists if the measurement says the defun does NOT signal: then the two paths may
already agree and the whole item is a documentation fix. Take the measurement first.

Whatever is chosen: one behaviour, pinned by a cross-backend case (`ci-spec.yaml`, so it runs
on four backends x scalar/`--simd`) and not only by a unit test, and `.kb/vec.md`'s
"Lane forms only where they equal the defun" bullet updated to say what `sqrt` does -- it is
the file that makes the claim this item contradicts.

## Related

- `.todo/758` -- the other `--simd` cross-backend disagreement found the same day, from
  `.todo/480`. Different mechanism (a reduction's partial final group) but the same shape:
  a contract `.kb/vec.md` states, an implementation half that does not honour it, and no case
  positioned to see it. Whoever takes one should read the other.
- `.kb/bfloat16.md`, the "Two findings the work turned up" paragraph -- where 747 left this,
  and where the sNaN finding beside it correctly stays (that one is a decision with a pin,
  not a defect).
