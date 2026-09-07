# 727: what the 2.1 us of an FFM downcall inside a native image IS

Taken 2026-09-07 on **GB10** (aarch64 Cortex-X925, 20 cores, CUDA 580, GraalVM 25.0.4 native-image,
OpenBLAS 0.3 at `OPENBLAS_NUM_THREADS=1`), base commit `4a0c8f5e9`. Every probe here is standalone
and NOT project code; `meta/` registers every downcall shape they bind, so one `-cp classes:meta`
serves all of them:

```sh
javac -d classes DowncallFloor.java && native-image -cp classes:meta --enable-native-access=ALL-UNNAMED -o downcall-floor DowncallFloor
javac -d classes ShapeFloor.java    && native-image -cp classes:meta --enable-native-access=ALL-UNNAMED -o shape-floor ShapeFloor
javac --add-modules org.graalvm.nativeimage -d classes CFunctionPointerFloor.java && native-image -cp classes:meta --enable-native-access=ALL-UNNAMED -o cfp-floor CFunctionPointerFloor
javac -d classes BuildTimeHandle.java && native-image -cp classes:meta --enable-native-access=ALL-UNNAMED '--initialize-at-build-time=BuildTimeHandle$Holder' -o build-time-handle BuildTimeHandle   # FAILS, see below
```

`-g -H:+UnlockExperimentalVMOptions -H:-DeleteLocalSymbols` on the first build is what gives `perf`
method names; without it the whole image is one symbol.

## 1. The attribution: SubstrateVM's method-handle interpreter

`perf record -F 4000 -g` over `.todo/artefacts/476-.../HandleConstancy` built as an image (the
2.08 us reproduces at 2.16 here). Of the calling thread's samples 97.9% are in the image's own code,
1.6% in the kernel and **0.06% in libcuda**. The flat profile:

| self % | symbol |
| --- | --- |
| 15.0 | `java.lang.invoke.LambdaForm::interpretName` |
| 11.1 | `com.oracle.svm.core.invoke.MethodHandleUtils::cast` |
| 9.4 | `com.oracle.svm.core.methodhandles.MethodHandleIntrinsicImpl::execute` |
| 6.7 | `java.lang.invoke.MethodHandle::invokeBasic` (SVM's substitution) |
| 3.8 | `LambdaForm::interpretWithArguments` |
| 3.5 | `Util_java_lang_invoke_MethodHandle::convertArgs` |
| 2.6 | `Util_java_lang_invoke_MethodHandle::invokeInternal` |
| 2.4 / 1.5 / 1.9 | `Class::searchFields`, `Class::reflectionData`, `arrayRegionEqualsS1S1` (a reflective member lookup by NAME) |
| 2.1 / 2.0 | `HashSet::contains`, `HashMap::getNode` |
| 1.5 | `jdk.internal.foreign.abi.DowncallLinker::invokeInterpBindings` (the JDK's interpreted binding path) |
| 1.2 / 1.0 | `Module::isExplicitlyExportedOrOpened`, `Module::allows` (per-call access checks) |
| 1.0 | `BindingInterpreter::unbox` |

The mechanism, from `svm.jar`'s sources (`svm.src.zip`,
`com/oracle/svm/core/methodhandles/Target_java_lang_invoke_LambdaForm.java`):

```java
/* We do not want invokers for lambda forms to be generated at runtime. */
@Substitute private boolean forceInterpretation() { return true; }
```

A `MethodHandle` created at run time has no AOT code, and SubstrateVM cannot spin bytecode, so its
`LambdaForm` is walked Name by Name (`interpretName`) with every argument boxed into an `Object[]`;
each `DirectMethodHandle` in the chain goes through `Util_java_lang_invoke_MethodHandle.invokeInternal`,
i.e. a REFLECTION accessor (`SubstrateMethodAccessor.invoke` / `FieldAccessor.get`) with
`convertArgs` boxing conversions, and each result through `MethodHandleUtils.cast`. The downcall
itself is the last Name -- `linkToNative` into a stub compiled at build time from
`reachability-metadata.json` -- and it is cheap; everything before it is the 2 us. A second thread,
`Reference Handler`, is busy the whole time (futex / condvar): the interpreter allocates per call
and the young collections wake it.

## 2. It is not libcuda, and it is roughly linear in the argument count -- `DowncallFloor.java`, `ShapeFloor.java`

Two million calls, ns each, steady state:

| target (arguments) | GraalVM JIT | native image |
| --- | --- | --- |
| `cuDriverGetVersion(int*)` (1) | 8.8 | 2103-2110 |
| same, `critical(true)` | 4.6 | 2140-2148 |
| libc `abs(int)` (1) | 7.3 | 2147-2153 |
| same, `critical(false)` | 2.7 | 2142-2147 |
| libc `getpid()` (0; a syscall) | 119.3 | 1860 |
| no downcall (the same loop) | 5.7 | 32.1 |

`getpid` is 119 ns on the JVM and 1860 in the image: the syscall is the JVM's cost and the
interpreter is the image's, and one argument adds ~290 ns. The shapes rontolisp issues, each
called so the callee returns at once (null handle / zero extent):

| shape (arguments) | GraalVM JIT | native image |
| --- | --- | --- |
| `cuCtxSetCurrent` (1) | 9.4 | 2133 |
| `cuMemcpyHtoD_v2` (3) | 15.4 | 2933 |
| `cuLaunchKernel` (11) | 17.1 | 6173-6185 |
| `cblas_dgemv`, critical (12) | 7.7 | 6414-6422 |
| `cblas_dgemm`, critical (14) | 6.9 | 7210-7220 |
| `cblas_dgemm`, plain (14) | 10.9 | 7029-7057 |

**~1.7 us a call plus ~0.4 us per argument.** `critical` is not cheaper in the image because the
transition it skips is a few ns; what it adds is a binding.

## 3. The transition is not it: SVM's own native-call route is 10.7 ns -- `CFunctionPointerFloor.java`

The same `cuDriverGetVersion` address, obtained through the FFM lookup at run time and invoked
through an `@InvokeCFunctionPointer` interface that native-image compiles at build time:

| route | ns |
| --- | --- |
| FFM downcall handle | 2104 |
| `CFunctionPointer`, with the thread transition | **10.7** |
| `CFunctionPointer`, `Transition.NO_TRANSITION` | 3.2 |
| no downcall | 32.8 |

So the whole 2.1 us is the run-time handle chain; an AOT-compiled call to the same pointer costs what
the JVM's does. Using this route in rontolisp needs the `org.graalvm.nativeimage` API at compile time
(a module of the GraalVM JDK, absent elsewhere) -- that is `.todo/729`. Word-typed values cannot cross
a lambda (`Expected Object but got Word for call argument`), which is why the probe's arms are static
methods.

## 4. It cannot be made a constant: the build FAILS -- `BuildTimeHandle.java`, `build-time-handle.error.txt`

The address-first shape, `Linker.downcallHandle(FunctionDescriptor)`, holds no native pointer, so a
holder class initialised at BUILD time can carry the handle in the image heap and AOT code sees a
constant. On the JVM the arm works (8.4 ns, address passed at run time). Under
`--initialize-at-build-time=BuildTimeHandle$Holder` native-image dies in analysis:

```
com.oracle.svm.core.util.VMError$HostedError: should not reach here: unexpected input could not be handled: linkToNative
	at com.oracle.svm.hosted.substitute.PolymorphicSignatureWrapperMethod.buildGraph(PolymorphicSignatureWrapperMethod.java:170)
```

The AOT inliner for a polymorphic-signature call on a constant handle has no case for a downcall,
and it is a crash rather than a fallback to the interpreter. With or without `critical`. This is the
half of `.todo/730` that is a bug report.

## 5. What it did to `--blas` in the binary -- `crossover.lisp`

`target/rontolisp crossover.lisp <flags>` on the interpreter, us per call, `--simd` lane kernel
against `--blas --simd` (OpenBLAS at one thread), 2*10^8 / work repetitions:

| `linalg:dot` n x n by n x n | 16 | 24 | 32 | 48 | 64 |
| --- | --- | --- | --- | --- | --- |
| lane kernel | 3.6 | 8.7 | 19.3 | 60.8 | 142 |
| library | 9.3 | 9.2 | 10.0 | 13.3 | 18 |

| `vec:matvec` n x n by n | 8 | 64 | 128 | 192 | 256 | 384 | 512 | 1024 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| lane kernel | 0.61 | 1.6-1.8 | 6.7 | 11.2 | 21.0 | 39.1 | 70.9 | 322 |
| library | 7.4 | 8.0 | 13.4 | 12.9 | 15.7 | 25.8 | 40.7 | 166 |

With `MIN_WORK` = 64 the flag took every one of these, so an 8x8 `vec:matvec` cost 7.4 us instead
of 0.6. The same program on the JVM (`java --add-modules jdk.incubator.vector -jar ... --blas --simd`)
is what the 64 was calibrated on: the library is 0.33-0.67 us a gemv against a 0.5-1.4 us lane
kernel from 16x16 up, and a 16x16x16 gemm 0.615 against 2.46. Now 2^15 (gemm) and 2^17 (gemv) in
the binary, `LinalgBlasKernels.minWork`; the JVM keeps 64. Re-run on the rebuilt binary: `--blas
--simd` is byte-for-byte the lane kernel's time at 16 and 24 (3.6 / 8.7 us) and at 128x128 and
256x256 (5.7 / 19.0), and the library's from 32x32x32 (10.0) and 384x384 (33.9) up.

## 6. What it did to `--gpu` in the binary -- `dot64-timed.lisp` under `nsys`

5000 x `(linalg:sum (linalg:dot a b))` at 64x64, `--gpu --simd`, the result read back each time:

| | native image | JVM (`--add-modules jdk.incubator.vector`) |
| --- | --- | --- |
| per call | **63.0 us** | 30.4 us |
| driver side (nsys `cuda_api_sum`) | 13.6 us: DtoH 6.8, launch 2.4, alloc 2.1, free 0.9 x 1.05, setCurrent 0.05 x 2 | 15.1 us |
| host side | 49.4 us | 15.3 us |

The 5.65 driver calls per member cost ~19 us of interpretation by section 2's floors; the rest of
the host-side gap is the AOT interpreter's own Lisp. The lane kernel in the same binary is 152 us at
this size, so `Gpu.POOLED_MIN_WORK` = 2^17 still sits where the device is unambiguously ahead (2.4x
against the JVM's 3-5x) and is unchanged; below it (n=48, declined) the two columns would tie.

## Files

- `DowncallFloor.java` -- by target: libcuda against libc, plain and critical, 0 and 1 argument.
- `ShapeFloor.java` -- the six shapes rontolisp issues, JIT against image.
- `CFunctionPointerFloor.java` -- SVM's `@InvokeCFunctionPointer` against the FFM handle (image only).
- `BuildTimeHandle.java`, `build-time-handle.error.txt` -- the constant-handle attempt and its crash.
- `crossover.lisp` (`SIZES`, `WHAT=dot|mv`), `dot64-timed.lisp` -- the interpreter-side measurements.
- `meta/` -- the reachability metadata all four Java probes bind against.
