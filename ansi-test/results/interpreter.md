# ANSI test suite -- interpreter

Suite: `ca06bd919661af162c67407c9d994e881870bdb3`

**11,325 / 19,484 tests pass (58.1%)** -- 2,878 fail, 5,281 signal an error.

7 top-level forms could not be read, 499 could not be evaluated, 4 did not terminate; every test those forms would have defined is missing from the counts above.

| chapter | tests | pass | fail | error | pass rate | top-level forms lost |
|---|---:|---:|---:|---:|---:|---:|
| arrays | 1,356 | 646 | 87 | 623 | 47.6% | 13 |
| characters | 259 | 173 | 18 | 68 | 66.8% | 13 |
| conditions | 673 | 383 | 228 | 62 | 56.9% | 13 |
| cons | 1,879 | 1,057 | 157 | 665 | 56.3% | 13 |
| data-and-control-flow | 1,428 | 1,046 | 213 | 169 | 73.2% | 14 |
| environment | 210 | 121 | 19 | 70 | 57.6% | 13 |
| eval-and-compile | 306 | 204 | 54 | 48 | 66.7% | 13 |
| files | 87 | 26 | 8 | 53 | 29.9% | 13 |
| hash-tables | 157 | 127 | 23 | 7 | 80.9% | 15 |
| iteration | 843 | 534 | 208 | 101 | 63.3% | 15 |
| misc | 740 | 642 | 20 | 78 | 86.8% | 13 |
| numbers | 1,444 | 934 | 86 | 424 | 64.7% | 27 |
| objects | 846 | 335 | 199 | 312 | 39.6% | 39 |
| packages | 492 | 166 | 122 | 204 | 33.7% | 31 |
| pathnames | 214 | 120 | 26 | 68 | 56.1% | 14 |
| printer | 543 | 227 | 127 | 189 | 41.8% | 50 |
| rctest | 0 | 0 | 0 | 0 | 0.0% | 15 |
| reader | 575 | 62 | 279 | 234 | 10.8% | 21 |
| sequences | 3,287 | 2,206 | 149 | 932 | 67.1% | 13 |
| streams | 759 | 225 | 82 | 452 | 29.6% | 58 |
| strings | 509 | 366 | 94 | 49 | 71.9% | 14 |
| structures | 1,030 | 577 | 194 | 259 | 56.0% | 38 |
| symbols | 1,144 | 848 | 244 | 52 | 74.1% | 14 |
| system-construction | 77 | 23 | 4 | 50 | 29.9% | 13 |
| types-and-classes | 626 | 277 | 237 | 112 | 44.2% | 15 |
| **total** | **19,484** | **11,325** | **2,878** | **5,281** | **58.1%** | **510** |

## Most frequent failure reasons

| count | reason |
|---:|---|
| 351 | `X expects keyword arguments :X, got: :X` |
| 245 | `The variable *MINI-UNIVERSE* is unbound` |
| 228 | `X expects keyword arguments :X:X:X, got: :X` |
| 208 | `The variable *UNIVERSE* is unbound` |
| 159 | `X: there is no class named X` |
| 107 | `UnsupportedOperationException: setf does not support place: X` |
| 93 | `Function expects 1 argument, got 2` |
| 89 | `X expects 2 arguments, got 4` |
| 80 | `The function FLOAT-RADIX is undefined` |
| 66 | `The function CLASS-PRECEDENCE-LIST-FOO is undefined` |
| 66 | `X is a macro or special operator, not a function` |
| 56 | `The function SET-UP-PACKAGES is undefined` |
| 54 | `The function NUNION is undefined` |
| 54 | `Unsupported type specifier: X` |
| 53 | `The function MAKE-TWO-WAY-STREAM is undefined` |
| 51 | `Unknown keyword argument: :X` |
| 50 | `LispEvalException: X cannot redefine the standard operator X` |
| 50 | `The variable *ARRAYS* is unbound` |
| 50 | `X supports :input and :output directions` |
| 49 | `The function NSET-EXCLUSIVE-OR is undefined` |
| 48 | `X: :X supports only the native default value` |
| 47 | `The function UNUSE-PACKAGE is undefined` |
| 46 | `The function FIND-METHOD is undefined` |
| 46 | `The function NINTERSECTION is undefined` |
| 43 | `The function NSET-DIFFERENCE is undefined` |
| 42 | `The variable #:FOR is unbound` |
| 41 | `The variable CALL-ARGUMENTS-LIMIT is unbound` |
| 40 | `The function MAKE-CONCATENATED-STREAM is undefined` |
| 40 | `X: :displaced-to is not supported` |
| 37 | `The variable ARRAY-RANK-LIMIT is unbound` |
| 33 | `The function MAKE-ECHO-STREAM is undefined` |
| 33 | `The function RATIONAL is undefined` |
| 31 | `The function COPY-STRUCTURE is undefined` |
| 31 | `UnsupportedOperationException: X :element-type must be the literal 'character or '(unsigned-byte 8)` |
| 31 | `X expects 2 arguments, got 6` |
| 29 | `The function BIT-AND is undefined` |
| 29 | `The function DEFINE-METHOD-COMBINATION is undefined` |
| 29 | `The function NAME-CHAR is undefined` |
| 29 | `The function NSUBST is undefined` |
| 28 | `The function ASSOC-IF-NOT is undefined` |

