# ANSI test suite -- interpreter

Suite: `ca06bd919661af162c67407c9d994e881870bdb3`

**11,456 / 19,484 tests pass (58.8%)** -- 2,876 fail, 5,152 signal an error.

7 top-level forms could not be read, 449 could not be evaluated, 4 did not terminate; every test those forms would have defined is missing from the counts above.

| chapter | tests | pass | fail | error | pass rate | top-level forms lost |
|---|---:|---:|---:|---:|---:|---:|
| arrays | 1,356 | 650 | 87 | 619 | 47.9% | 11 |
| characters | 259 | 173 | 18 | 68 | 66.8% | 11 |
| conditions | 673 | 383 | 228 | 62 | 56.9% | 11 |
| cons | 1,879 | 1,057 | 156 | 666 | 56.3% | 11 |
| data-and-control-flow | 1,428 | 1,062 | 215 | 151 | 74.4% | 12 |
| environment | 210 | 121 | 19 | 70 | 57.6% | 11 |
| eval-and-compile | 306 | 204 | 54 | 48 | 66.7% | 11 |
| files | 87 | 26 | 8 | 53 | 29.9% | 11 |
| hash-tables | 157 | 127 | 23 | 7 | 80.9% | 13 |
| iteration | 843 | 536 | 208 | 99 | 63.6% | 13 |
| misc | 740 | 644 | 19 | 77 | 87.0% | 11 |
| numbers | 1,444 | 1,023 | 81 | 340 | 70.8% | 25 |
| objects | 846 | 335 | 208 | 303 | 39.6% | 37 |
| packages | 492 | 166 | 122 | 204 | 33.7% | 29 |
| pathnames | 214 | 120 | 26 | 68 | 56.1% | 12 |
| printer | 543 | 228 | 128 | 187 | 42.0% | 48 |
| rctest | 0 | 0 | 0 | 0 | 0.0% | 13 |
| reader | 575 | 62 | 279 | 234 | 10.8% | 19 |
| sequences | 3,287 | 2,206 | 149 | 932 | 67.1% | 11 |
| streams | 759 | 225 | 82 | 452 | 29.6% | 56 |
| strings | 509 | 366 | 94 | 49 | 71.9% | 12 |
| structures | 1,030 | 577 | 194 | 259 | 56.0% | 36 |
| symbols | 1,144 | 862 | 235 | 47 | 75.3% | 12 |
| system-construction | 77 | 23 | 4 | 50 | 29.9% | 11 |
| types-and-classes | 626 | 280 | 239 | 107 | 44.7% | 13 |
| **total** | **19,484** | **11,456** | **2,876** | **5,152** | **58.8%** | **460** |

## Most frequent failure reasons

| count | reason |
|---:|---|
| 352 | `X expects keyword arguments :X, got: :X` |
| 245 | `The variable *MINI-UNIVERSE* is unbound` |
| 228 | `X expects keyword arguments :X:X:X, got: :X` |
| 208 | `The variable *UNIVERSE* is unbound` |
| 161 | `X: there is no class named X` |
| 107 | `UnsupportedOperationException: setf does not support place: X` |
| 93 | `Function expects 1 argument, got 2` |
| 89 | `X expects 2 arguments, got 4` |
| 80 | `The function FLOAT-RADIX is undefined` |
| 67 | `The function CLASS-PRECEDENCE-LIST-FOO is undefined` |
| 66 | `X is a macro or special operator, not a function` |
| 56 | `The function SET-UP-PACKAGES is undefined` |
| 54 | `The function NUNION is undefined` |
| 53 | `The function MAKE-TWO-WAY-STREAM is undefined` |
| 51 | `Unknown keyword argument: :X` |
| 50 | `LispEvalException: X cannot redefine the standard operator X` |
| 50 | `The variable *METHODS* is unbound` |
| 50 | `X supports :input and :output directions` |
| 49 | `The function NSET-EXCLUSIVE-OR is undefined` |
| 48 | `X: :X supports only the native default value` |
| 47 | `The function UNUSE-PACKAGE is undefined` |
| 46 | `The function FIND-METHOD is undefined` |
| 46 | `The function NINTERSECTION is undefined` |
| 43 | `The function NSET-DIFFERENCE is undefined` |
| 42 | `The variable #:FOR is unbound` |
| 40 | `The function MAKE-CONCATENATED-STREAM is undefined` |
| 40 | `X: :displaced-to is not supported` |
| 33 | `The function MAKE-ECHO-STREAM is undefined` |
| 33 | `The function RATIONAL is undefined` |
| 32 | `X expects 2 arguments, got 6` |
| 31 | `The function COPY-STRUCTURE is undefined` |
| 31 | `UnsupportedOperationException: X :element-type must be the literal 'character or '(unsigned-byte 8)` |
| 29 | `The function BIT-AND is undefined` |
| 29 | `The function DEFINE-METHOD-COMBINATION is undefined` |
| 29 | `The function NAME-CHAR is undefined` |
| 29 | `The function NSUBST is undefined` |
| 28 | `The function ASSOC-IF-NOT is undefined` |
| 28 | `The function BIT-ANDC1 is undefined` |
| 28 | `The function BIT-ANDC2 is undefined` |
| 28 | `The function BIT-EQV is undefined` |

