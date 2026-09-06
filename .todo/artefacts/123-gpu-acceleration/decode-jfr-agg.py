#!/usr/bin/env python3
"""Where the HOST side of an `examples/llm` decode step goes, from a JFR recording (todo-718).

The device half is `decode-per-token.py`; this is the other half, and the one that named
the residency guards. Record with the JVM's own sampler, then print the samples with deep
stacks (the default five frames lose the Lisp function under a SIMD kernel or a guard):

    RONTOLISP_THREADS=1 java --enable-native-access=ALL-UNNAMED --add-modules jdk.incubator.vector \
      -Xmx16g -XX:StartFlightRecording=filename=gpu-1t.jfr,settings=profile,jdk.ExecutionSample#period=2ms,jdk.NativeMethodSample#period=2ms \
      -cp /tmp/g Llama Qwen3.5-0.8B-BF16.gguf -m chat -t 0 -n 64 -w bf16 -i "Tell me a short story about a cat."
    jfr print --events jdk.ExecutionSample,jdk.NativeMethodSample --stack-depth 64 gpu-1t.jfr > gpu-1t.txt
    python3 decode-jfr-agg.py gpu-1t.txt [thread=main]

Record at ONE thread: the sampler takes a handful of Java threads per tick, so with sixteen
spinning workers the main thread's share of the samples says nothing about its share of the
time. The decode window is from the first to the last sample of the thread whose stack
mentions GENERATE; every sample of that thread inside it counts, Java and native alike, so
the shares are of wall time on that thread. Each sample is filed under its TOP frame's
category and under the innermost Lisp-level frame (a `Llama.` method that is not a runtime
helper) -- the function the time belongs to, whichever guard or kernel it was spent in.
Multiply a share by the window over the forward count (85 for a 21-id prompt and `-n 64`)
for milliseconds per forward, JIT warm-up included.
"""
import sys, re
from collections import Counter

path = sys.argv[1]
thread = sys.argv[2] if len(sys.argv) > 2 else "main"
blocks = open(path).read().split("\njdk.")
helper = re.compile(r"^Llama\.(_|\$pct|lambda|main|<)")
samples = []
for b in blocks:
    if f'sampledThread = "{thread}"' not in b:
        continue
    t = re.search(r"startTime = (\d\d):(\d\d):(\d\d)\.(\d\d\d)", b)
    m = re.search(r"stackTrace = \[(.*?)\]", b, re.S)
    if not t or not m:
        continue
    h, mi, s, ms = map(int, t.groups())
    at = ((h * 60 + mi) * 60 + s) + ms / 1000.0
    frames = [f.strip() for f in m.group(1).strip().split("\n") if f.strip() and f.strip() != "..."]
    samples.append((at, b.startswith("NativeMethodSample"), frames))
samples.sort(key=lambda x: x[0])
gen = [at for at, nat, fr in samples if any("GENERATE" in f for f in fr)]
if not gen:
    sys.exit("no GENERATE sample")
lo, hi = gen[0], gen[-1]
win = [(nat, fr) for at, nat, fr in samples if lo <= at <= hi]
n = len(win)
print(f"thread {thread}: {len(samples)} samples, decode window {hi - lo:.2f} s, {n} samples in it "
      f"({sum(1 for nat, fr in win if nat)} native)")


def category(frames):
    top = frames[0]
    if "memcpyDtoHPinned" in top or "RontoLispGpuCudaDriver" in top:
        return "device wait (DtoH copy / driver call)"
    if top.startswith("RontoLispGpu"):
        return "residency guards (materialize / written per element)"
    if "RontoLispSimdBridge" in top or "jdk.incubator.vector" in top \
            or any("RontoLispSimdBridge.matvec" in f for f in frames[:6]):
        return "simd kernels (matvec and the element-wise members)"
    return "lisp code (typed loops, aref/aset, boxing)"


cat, lisp, top, cat_by_fn = Counter(), Counter(), Counter(), Counter()
for nat, frames in win:
    c = category(frames)
    cat[c] += 1
    top[frames[0]] += 1
    owner = next((f for f in frames if f.startswith("Llama.") and not helper.match(f)), frames[0])
    owner = owner.split("(")[0]
    lisp[owner] += 1
    cat_by_fn[(owner, c)] += 1

print("--- by category")
for c, k in cat.most_common():
    print(f"{100 * k / n:5.1f}%  {k:5d}  {c}")
print("--- by innermost Lisp function (and its categories)")
for f, k in lisp.most_common(14):
    parts = ", ".join(f"{100 * v / n:.1f}% {c.split(' (')[0]}" for (o, c), v in cat_by_fn.most_common() if o == f)
    print(f"{100 * k / n:5.1f}%  {k:5d}  {f}   [{parts}]")
print("--- by top frame")
for f, k in top.most_common(16):
    print(f"{100 * k / n:5.1f}%  {k:5d}  {f}")
