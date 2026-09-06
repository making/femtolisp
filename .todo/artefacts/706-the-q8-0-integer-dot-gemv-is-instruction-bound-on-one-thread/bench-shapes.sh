#!/usr/bin/env bash
# The block-dot shapes of .todo/706, each timed in ITS OWN JVM under both JITs, one
# thread, against the shipped f32 GEMV and the shipped Q8_0 kernel of the tree this is
# compiled against.
#
#   ./mvnw -o test-compile                       # first, from the repo root
#   .todo/artefacts/706-.../bench-shapes.sh [rows cols rounds] [variant-substring...]
#
# One JVM per variant, deliberately: the shapes share helper methods (s8, lo, step), and
# a helper C2 has already compiled standalone for one variant is refused inlining into
# the next ("already compiled into a big method"), which boxes its vectors -- the B
# shape measured 1.04x and then 0.33x of f32 in one JVM, depending on what ran before
# it. A shape's number is only its own when nothing else ran in that JVM.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$here/../../.." && pwd)"
out="${TMPDIR:-/tmp}/rontolisp-706-probe"
rows="${1:-4096}"
cols="${2:-4096}"
rounds="${3:-9}"
shift $(( $# < 3 ? $# : 3 ))
variants=("$@")
if [ ${#variants[@]} -eq 0 ]; then
	variants=("q8 shipped" "q8 B (" "q8 Bx (" "int-only" "scale-only" "q8 Bs (" "q8 G" "q8 H (" "q8 Hs" "q8 E" "q8 R" "q8 Q" "Bx2" "Bs2" "Bs4" "Bs-vh" "Bs-u2")
fi

mkdir -p "$out"
javac --add-modules jdk.incubator.vector -cp "$root/target/classes" -d "$out" "$here"/Q8Probe.java "$here"/Q8GemvBenchJit.java 2>&1 | grep -v -i warning || true
cp="$out:$root/target/classes"

uptime
for jit in c2 graal; do
	flags=()
	if [ "$jit" = c2 ]; then
		flags=(-XX:-UseJVMCICompiler)
	fi
	echo "===== $jit ${rows}x${cols}"
	for v in "${variants[@]}"; do
		java "${flags[@]}" --add-modules jdk.incubator.vector -cp "$cp" am.ik.rontolisp.eval.Q8Probe "$rows" "$cols" "$rounds" "$v" 2>&1 | grep -E '^(f32|q8) ' | grep -v 'f32 lanes' || true
	done
	java "${flags[@]}" --add-modules jdk.incubator.vector -cp "$cp" am.ik.rontolisp.eval.Q8Probe "$rows" "$cols" "$rounds" "q8 shipped" 2>&1 | grep -E '^f32 ' || true
done
uptime
