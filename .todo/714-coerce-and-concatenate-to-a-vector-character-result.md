# 714. `coerce` / `concatenate` to `(vector character)` answer a GENERAL vector

Difficulty: Low

Found 2026-09-06 closing `.todo/707`, which gave `coerce` and `concatenate` the packed
FLOAT element types and left `character` -- the one remaining specialized
`ArrayElementTypes` code with no arm of its own -- where it was:

```lisp
(array-element-type (coerce '(#\a #\b) '(vector character)))          ; => T
(array-element-type (concatenate '(vector character) "ab"))           ; => T
(coerce '(#\a #\b) '(vector character))                               ; => #(a b)
```

SBCL answers `CHARACTER` for the first two and `"ab"` for the third: a
`(vector character)` result IS a string. We answer a general vector of characters, so
`array-element-type` forgets the request, `typep` against
`(simple-array character (*))` is false, and the value does not print or `stringp` as a
string.

`707` deliberately did not fix it, and the reason is worth keeping: the float widths only
changed what these operators REMEMBER (the value printed the same either way), while this
changes what they ANSWER -- a string rather than a vector, with a different identity, a
different printed form and a different `stringp`. It also collides with the STRING family,
which already builds exactly that value from exactly those elements.

## Do

1. Decide the one question first: is `(coerce '(#\a) '(vector character))` the `'string`
   family's value, or a mutable character vector (`(make-array 1 :element-type 'character)`,
   which every backend already builds and prints as a string)? The second is what
   `make-array` answers for the same element type, so it is the answer that keeps the
   designator meaning ONE thing -- but check what `stringp` / `typep 'simple-string` /
   `%string-concat` do with it on all four backends before committing.
2. Route it through `ConcatenateForms.packedVectorCall`'s remaining arm (the code is
   already in `ResultSpec`; today it falls into the general `(coerce ... 'vector)`), so
   `coerce` and `concatenate` cannot diverge.
3. The `#'concatenate` wrapper's runtime dispatch has to match arm for arm, as the float
   codes do.
4. Pin it on every engine and drop the `CHARACTER` exemption from
   `eval/PackedFloatReachabilityTest#everySpecializedElementTypeCodeSurvivesCoerceAndConcatenate`,
   which names this item as the reason it skips the code.
