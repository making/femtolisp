# 729: the binary's downcalls through SubstrateVM's own AOT route -- one shape end to end

Taken 2026-09-10 on **GB10** (aarch64 Cortex-X925, 20 cores, CUDA 580, GraalVM 25.0.4
native-image, OpenBLAS 0.3 at `OPENBLAS_NUM_THREADS=1`), base commit `9f5019125`, load average
under 0.1 for the floors and crossovers. Every probe here is standalone and NOT project code. The
outcome is in `.kb/native-downcalls.md`; this directory is the raw record.

```sh
javac --add-modules org.graalvm.nativeimage -d classes CfpShapeFloor.java && \
  native-image -cp classes:../727-an-ffm-downcall-costs-2-1-us-inside-a-native-image/meta \
    --enable-native-access=ALL-UNNAMED -o cfp-shape-floor CfpShapeFloor
OPENBLAS_NUM_THREADS=1 ./cfp-shape-floor 256   # then 64
```

## 1. The six shapes rontolisp issues, FFM handle against `@InvokeCFunctionPointer` -- `CfpShapeFloor.java`

The same address (found by the FFM lookup) through both routes in the same image, one million
calls each, three rounds, ns per call; the callee returns at once (null handle / zero extent). The
two BLAS shapes pin their three heap-array operands per call (`PinnedObject`), which is what a
substituted `LinalgBlasKernels.gemv` has to do.

| shape (arguments) | FFM handle | `CFunctionPointer` | JVM (727, for reference) |
| --- | --- | --- | --- |
| `cuCtxSetCurrent` (1) | 2099-2117 | 11.4 | 9.4 |
| `cuMemcpyHtoD_v2` (3) | 2944-2952 | 16.4-16.5 | 15.4 |
| `cuLaunchKernel` (11) | 6168-6175 | 16.1 | 17.1 |
| `cblas_dgemv` (12, 3 pins) | 6445-6462 | 88.5-89.0; 82.4-84.3 with `NO_TRANSITION` | 7.7 critical |
| `cblas_dgemm` (14, 3 pins) | 7257-7269 | 91.4-91.5 | 6.9 critical |

A REAL `dgemv`, ns per call, three rounds:

| n x n by n | FFM critical | `CFunctionPointer`, pinned | pinned, `NO_TRANSITION` |
| --- | --- | --- | --- |
| 64 | 6998-7003 | 505-507 | 499-502 |
| 256 | 14172-14619 | 7170-8104 | 7197-8067 |

The route is real at the call: the CUDA shapes are the JVM's floor, the BLAS shapes are ~90 ns of
which the three pins are ~75.

Two image-build failures on the way, both fatal in analysis and silent at `javac`: a Word-typed
value in a `static final` field (`StaticFinalFieldFoldingPhase`: "missing StateSplitProxy"), and --
from 727 -- a Word-typed value crossing a lambda. The arms are static methods and the pointer is made
inside each from a `long`.

## 2. One shape end to end: `Target_LinalgBlasKernels.gemv` under `-Pnative`

The prototype (a `-Pnative` source set `src/native/java`, `org.graalvm.sdk:nativeimage` provided,
one `@Substitute` for the double gemv taking the address the FFM bind recorded) built in the
normal 1:25 min and was measured with `../727-.../crossover.lisp` (`WHAT=mv`), us per call, on the
binary built at `9f5019125` ("before") and on the prototype:

| `vec:matvec` n x n by n | 8 | 16 | 32 | 64 | 128 | 192 | 256 | 384 | 512 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `--simd` lane kernel, before | 0.745 | 0.720 | 0.911 | 1.741 | 4.997 | 10.32 | 18.03 | 39.09 | 69.55 |
| `--blas --simd`, before (2^17 floor) | 0.785 | 0.760 | 0.963 | 1.823 | 5.079 | 10.51 | 18.03 | 27.29 | 40.68 |
| `--simd` lane kernel, prototype | 0.750 | 0.730 | 0.916 | 1.700 | 4.915 | 10.32 | 17.70 | 39.82 | 70.87 |
| `--blas --simd`, prototype (64 floor) | 0.790 | 0.730 | 0.829 | 1.126 | 2.867 | 4.793 | 7.866 | 16.22 | 30.18 |

The crossover returned to the JVM's, so the seam was widened to the four products (`gemm`,
`gemmF`, `gemv`, `gemvF`) and the threshold to one number. The final binary's numbers are in
`.kb/native-downcalls.md`; `WHAT=dot` there: 8x8x8 0.78 against 0.95, 16 0.96 against 2.97, 32 2.29
against 18.8, 64 10.5 against 142.

## 3. Correctness -- `check.lisp`, `check-output.txt`

Exact-integer operands at both widths, `vec:matvec`, `vec:matvec-into` and the three `linalg:dot`
shapes at n = 3, 4, 8, 9, 33 (plain and `--blas`) and up to 300 (`--simd` and `--blas --simd`): the
four flag sets print identical bytes on the final binary (`SIZES="(3 4 8 9 33 130 257 300)"` for the
`--simd` pair; the scalar defun at 300 is minutes in the interpreter, so the plain pair stops at 33).

## 4. Why the CUDA half was not widened

`examples/llm/llm.lisp` on the binary, Qwen3.5-0.8B BF16 GGUF, `--gpu --simd -- ... -m chat -t 0
-n 48 -w bf16`: load 45.2 s, then **0.12 tok/s** (`achieved tok/s: 0.12 (last 14: 0.12)`), 8.3 s a
forward; wall 469 s. A `-n 16` run (fewer positions than the chat prompt, so no generation) took
203 s under `--gpu --simd` and 557 s under `--simd` -- the prompt alone at 5 and 17 s a position.
The JVM class output does the same forward in 25 ms. The ~1300 driver calls of a forward are ~5 ms
of interpretation: 0.06% of the binary's forward. The route is real (section 1) and the seam is
built (section 2); the gain is in a runtime whose own Lisp is two orders above it.

## Files

- `CfpShapeFloor.java` -- section 1; binds against 727's `meta/`.
- `check.lisp`, `check-output.txt` -- section 3 (`SIZES` env).
- The crossover program is 727's `crossover.lisp`; the seam is `src/native/java/`, `pom.xml`
  (`native` profile) and `NativeSubstitutionsTest`.
