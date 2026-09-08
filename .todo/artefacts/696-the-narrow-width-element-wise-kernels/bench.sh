#!/usr/bin/env bash
# Does the bfloat16 NARROWING vectorize? -- the measurement .todo/696 demands before any
# element-wise bf16 kernel is written.
#
# Both JITs, for the reason .todo/482 round 2 found: the same Vector API source ran at
# 1.51x under Graal and 0.20x under C2 because the method overran C2's inlining budget and
# every vector was boxed, silently. A shape that is fast under one and boxed under the
# other is not done.
#
#   ./mvnw -o test-compile          # first, from the repo root
#   .todo/artefacts/696-the-narrow-width-element-wise-kernels/bench.sh
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
cp="$root/target/classes:$root/target/test-classes"

run() { # run <label> <extra jvm args...>
	local label="$1"
	shift
	echo "############ $label"
	java "$@" --add-modules jdk.incubator.vector -cp "$cp" am.ik.rontolisp.eval.Bf16NarrowBench
}

# The operand-pair sweep (all 65536x65536 pairs per operation, ~1 min) is a correctness
# question and JIT-independent, so it runs once rather than per JIT.
echo "############ Bf16NarrowBench / operand-pair sweep"
java --add-modules jdk.incubator.vector -cp "$cp" am.ik.rontolisp.eval.Bf16NarrowBench pairs

run "Bf16NarrowBench / Graal"
run "Bf16NarrowBench / C2" -XX:-UseJVMCICompiler
