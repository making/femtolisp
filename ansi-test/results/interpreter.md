# ANSI test suite -- interpreter

Suite: `ca06bd919661af162c67407c9d994e881870bdb3`

**10,809 / 19,461 tests pass (55.5%)** -- 2,823 fail, 5,829 signal an error.

7 top-level forms could not be read, 575 could not be evaluated, 4 did not terminate; every test those forms would have defined is missing from the counts above.

| chapter | tests | pass | fail | error | pass rate | top-level forms lost |
|---|---:|---:|---:|---:|---:|---:|
| arrays | 1,356 | 646 | 87 | 623 | 47.6% | 16 |
| characters | 259 | 173 | 18 | 68 | 66.8% | 16 |
| conditions | 673 | 381 | 226 | 66 | 56.6% | 16 |
| cons | 1,879 | 1,056 | 157 | 666 | 56.2% | 16 |
| data-and-control-flow | 1,428 | 1,012 | 213 | 203 | 70.9% | 17 |
| environment | 210 | 119 | 11 | 80 | 56.7% | 16 |
| eval-and-compile | 306 | 204 | 54 | 48 | 66.7% | 16 |
| files | 87 | 26 | 8 | 53 | 29.9% | 16 |
| hash-tables | 157 | 126 | 23 | 8 | 80.3% | 18 |
| iteration | 843 | 521 | 207 | 115 | 61.8% | 18 |
| misc | 740 | 588 | 18 | 134 | 79.5% | 16 |
| numbers | 1,444 | 727 | 75 | 642 | 50.3% | 30 |
| objects | 846 | 333 | 189 | 324 | 39.4% | 42 |
| packages | 492 | 48 | 51 | 393 | 9.8% | 34 |
| pathnames | 214 | 120 | 26 | 68 | 56.1% | 17 |
| printer | 543 | 221 | 123 | 199 | 40.7% | 53 |
| rctest | 0 | 0 | 0 | 0 | 0.0% | 18 |
| reader | 575 | 59 | 286 | 230 | 10.3% | 24 |
| sequences | 3,287 | 2,191 | 149 | 947 | 66.7% | 16 |
| streams | 759 | 224 | 82 | 453 | 29.5% | 61 |
| strings | 509 | 366 | 94 | 49 | 71.9% | 17 |
| structures | 1,007 | 577 | 190 | 240 | 57.3% | 42 |
| symbols | 1,144 | 795 | 293 | 56 | 69.5% | 17 |
| system-construction | 77 | 23 | 4 | 50 | 29.9% | 16 |
| types-and-classes | 626 | 273 | 239 | 114 | 43.6% | 18 |
| **total** | **19,461** | **10,809** | **2,823** | **5,829** | **55.5%** | **586** |

## Most frequent failure reasons

| count | reason |
|---:|---|
| 350 | `X expects keyword arguments :X, got: :X` |
| 233 | `The variable *MINI-UNIVERSE* is unbound` |
| 228 | `X expects keyword arguments :X:X:X, got: :X` |
| 217 | `The function MAKE-PACKAGE is undefined` |
| 202 | `The variable *UNIVERSE* is unbound` |
| 156 | `X: there is no class named X` |
| 101 | `UnsupportedOperationException: setf does not support place: X` |
| 93 | `Function expects 1 argument, got 2` |
| 89 | `X expects 2 arguments, got 4` |
| 80 | `The function FLOAT-RADIX is undefined` |
| 80 | `The variable *NUMBERS* is unbound` |
| 75 | `complex numbers are not supported (imaginary part X)` |
| 65 | `The variable *FLOATS* is unbound` |
| 63 | `The function CLASS-PRECEDENCE-LIST-FOO is undefined` |
| 62 | `X is a macro or special operator, not a function` |
| 56 | `The function SET-UP-PACKAGES is undefined` |
| 56 | `The variable #C is unbound` |
| 54 | `The function NUNION is undefined` |
| 53 | `The function MAKE-TWO-WAY-STREAM is undefined` |
| 51 | `Unknown keyword argument: :X` |
| 50 | `LispEvalException: X cannot redefine the standard operator X` |
| 50 | `The variable *REALS* is unbound` |
| 50 | `X supports :input and :output directions` |
| 49 | `The function NSET-EXCLUSIVE-OR is undefined` |
| 48 | `X: :X supports only the native default value` |
| 46 | `The function FIND-METHOD is undefined` |
| 46 | `The function NINTERSECTION is undefined` |
| 46 | `Unsupported type specifier: X` |
| 43 | `The function NSET-DIFFERENCE is undefined` |
| 40 | `The function MAKE-CONCATENATED-STREAM is undefined` |
| 40 | `X: :displaced-to is not supported` |
| 39 | `The variable CALL-ARGUMENTS-LIMIT is unbound` |
| 37 | `The variable ARRAY-RANK-LIMIT is unbound` |
| 36 | `The variable #:FOR is unbound` |
| 34 | `X expects a tag: (X X)` |
| 33 | `The function MAKE-ECHO-STREAM is undefined` |
| 31 | `The function COPY-STRUCTURE is undefined` |
| 31 | `UnsupportedOperationException: X :element-type must be the literal 'character or '(unsigned-byte 8)` |
| 31 | `X expects 2 arguments, got 6` |
| 30 | `The function DELETE-PACKAGE is undefined` |

