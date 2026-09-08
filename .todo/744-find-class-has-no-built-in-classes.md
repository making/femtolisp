# `find-class` has no built-in classes, and `class-of` on a general array answers `T`

Difficulty: Medium

```lisp
(find-class 'array nil)            ; => NIL   (SBCL: #<BUILT-IN-CLASS ARRAY>)
(find-class 'vector nil)           ; => NIL
(find-class 'bit-vector nil)       ; => NIL
(find-class 'number nil)           ; => NIL
(find-class 'real nil)             ; => NIL
(find-class 'structure-object nil) ; => NIL
(find-class 'built-in-class nil)   ; => NIL
(class-of (make-array 3))          ; => #<STANDARD-CLASS :NAME T ...>   -- should be ARRAY
```

`find-class` and `class-of` DO answer for `integer`, `string`, `symbol`, `cons`
and the rest (`.kb/clos.md`, "the STATIC metaobject subset is IN") -- the table
is simply missing the array/number branch of the built-in class lattice, and
`class-of` falls back to `T` for anything it does not know.

## Cost

**156 tests** on the ANSI suite at `ca06bd9` (2026-09-08, TEST-level `ERROR`
lines): `array` 112, `vector` 19, `bit-vector` 11, `built-in-class` 6, `number`
2, `structure-object` 1, `real` 1. 104 of the 112 are in the `arrays` chapter
alone, where it is the single largest failure row -- larger than the whole
`bit-and` family.

This is NOT the MOP reflection that `.kb/clos.md` puts out of scope
(`find-method`, `compute-applicable-methods`, `ensure-generic-function`,
runtime class construction). It is one more row per name in a table that already
exists and is already static.

## How to do it

The class table is built by `expandTopLevelDefinitions` / `%class-meta-table%`
on the compile paths and by the interpreter's registry; `.kb/clos.md`
("A user method on a BUILT-IN name", the `find-class`/`class-of` section) is the
file to change and to name the pinning test in. Add the missing built-in class
names with their CPLs, and make `class-of` answer the narrowest of them for an
array / bit vector / general number rather than `T`. Cross-backend, so it needs a
`ci-spec.yaml` case.

`subtypep` already knows the same lattice, and 136 `SUBTYPEP.*` tests fail for a
different reason (`.todo/214`, the missing valid-p second value) -- keep the two
apart when measuring.

Found while re-reading the ANSI report for `.todo/715` (2026-09-08).
