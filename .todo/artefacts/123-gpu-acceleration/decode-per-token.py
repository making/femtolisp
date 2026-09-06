#!/usr/bin/env python3
"""Bucket an nsys export of an `examples/llm` decode run per FORWARD PASS (todo-718, 2026-09-06).

A forward pass has no marker of its own, so the boundary is the launch of the classifier-head
kernel -- the largest grid, run exactly once per forward. Per window: device kernel time by
(kernel, gridX), memcpy counts and bytes, and the CUDA API time on the calling thread by API
name; medians over the steady windows (the first SKIP are the JIT warming up), then the whole
timeline so a slow window can be placed. Head launches are fewer than forwards: the head is
declined at first sight and uploaded at the second, and the printed `tok/s` clock starts one
forward earlier still (todo-724).

    cd examples/llm
    java -jar ../../target/rontolisp-0.1.0-SNAPSHOT-exec.jar llm.lisp -o /tmp/g/Llama.class \
      --class-name Llama --gpu --simd --parallel
    RONTOLISP_THREADS=16 nsys profile -t cuda -s none --cpuctxsw=none -o qwen-gpu \
      java --enable-native-access=ALL-UNNAMED --add-modules jdk.incubator.vector -Xmx16g \
      -cp /tmp/g Llama Qwen3.5-0.8B-BF16.gguf -m chat -t 0 -n 64 -w bf16 \
      -i "Tell me a short story about a cat."
    nsys export --type sqlite -o qwen-gpu.sqlite qwen-gpu.nsys-rep
    python3 decode-per-token.py qwen-gpu.sqlite [SKIP=4]

`-t cuda -s none` keeps the profiler out of the JVM's threads; it still adds a few microseconds
to each of the ~1100 API calls a forward makes, so the window period here is ~10 ms above the
unprofiled forward (take that from the difference of a 128- and a 64-token run).
"""
import sqlite3, sys, statistics as st
from collections import defaultdict

db = sqlite3.connect(sys.argv[1])
skip = int(sys.argv[2]) if len(sys.argv) > 2 else 4
names = dict(db.execute("select id, value from StringIds"))

kern = [(s, e, gx, names[n]) for s, e, gx, n in
        db.execute("select start, end, gridX, shortName from CUPTI_ACTIVITY_KIND_KERNEL order by start")]
heads = max(kern, key=lambda k: k[2])[2]
bounds = [s for s, e, gx, n in kern if gx == heads]
print(f"kernels {len(kern)}, head grid {heads}, head launches {len(bounds)}")
if len(bounds) < skip + 3:
    sys.exit("too few forwards")

memcpy = list(db.execute("select start, end, bytes, copyKind from CUPTI_ACTIVITY_KIND_MEMCPY order by start"))
api = [(s, e, names[n]) for s, e, n in
       db.execute("select start, end, nameId from CUPTI_ACTIVITY_KIND_RUNTIME order by start")]


def windows(rows):
    out = [[] for _ in range(len(bounds) - 1)]
    i = 0
    for r in rows:
        t = r[0]
        while i < len(bounds) - 1 and t >= bounds[i + 1]:
            i += 1
        if i >= len(bounds) - 1:
            break
        if t >= bounds[i]:
            out[i].append(r)
    return out


kw, mw, aw = windows(kern), windows(memcpy), windows(api)
steady = range(skip, len(bounds) - 1)
period = [(bounds[i + 1] - bounds[i]) / 1e6 for i in steady]
print(f"forward period ms: median {st.median(period):.2f}  min {min(period):.2f}  max {max(period):.2f}  (n={len(period)})")


def med(f):
    return st.median([f(i) for i in steady])


print(f"kernel time/forward ms: {med(lambda i: sum(e - s for s, e, gx, n in kw[i]) / 1e6):.2f}  launches {med(lambda i: len(kw[i])):.0f}")
bygrid = defaultdict(list)
for i in steady:
    acc, cnt = defaultdict(float), defaultdict(int)
    for s, e, gx, n in kw[i]:
        acc[(n, gx)] += (e - s) / 1e6
        cnt[(n, gx)] += 1
    for k in acc:
        bygrid[k].append((acc[k], cnt[k]))
print("  by (kernel, gridX): median ms/forward, median launches/forward")
for k, v in sorted(bygrid.items(), key=lambda kv: -st.median(x[0] for x in kv[1])):
    print(f"    {k[0]:10s} grid {k[1]:6d}: {st.median(x[0] for x in v):6.2f} ms  x{st.median(x[1] for x in v):.0f}")

for kind, label in ((1, "HtoD"), (2, "DtoH")):
    print(f"memcpy {label}/forward: count {med(lambda i: sum(1 for r in mw[i] if r[3] == kind)):.0f}  "
          f"MB {med(lambda i: sum(r[2] for r in mw[i] if r[3] == kind) / 1e6):.2f}  "
          f"device ms {med(lambda i: sum(r[1] - r[0] for r in mw[i] if r[3] == kind) / 1e6):.2f}")
    sizes = defaultdict(int)
    for i in steady:
        for r in mw[i]:
            if r[3] == kind:
                sizes[r[2]] += 1
    top = sorted(sizes.items(), key=lambda kv: -kv[0] * kv[1])[:8]
    print("   sizes (bytes: count/forward): " + ", ".join(f"{b}: {c / len(steady):.1f}" for b, c in top))

print("CUDA API time on the calling thread, ms/forward (median), calls/forward:")
byapi = defaultdict(list)
for i in steady:
    acc, cnt = defaultdict(float), defaultdict(int)
    for s, e, n in aw[i]:
        acc[n] += (e - s) / 1e6
        cnt[n] += 1
    for n in acc:
        byapi[n].append((acc[n], cnt[n]))
for n, v in sorted(byapi.items(), key=lambda kv: -st.median(x[0] for x in kv[1])):
    print(f"    {n:22s} {st.median(x[0] for x in v):6.2f} ms  x{st.median(x[1] for x in v):.0f}")
print(f"    {'TOTAL':22s} {med(lambda i: sum(e - s for s, e, n in aw[i]) / 1e6):6.2f} ms")

print("--- timeline: head launches (s from the first kernel), then every window's period (ms)")
t0 = kern[0][0]
print(" ".join(f"{(b - t0) / 1e9:.2f}" for b in bounds))
print(" ".join(f"{(bounds[i + 1] - bounds[i]) / 1e6:.0f}" for i in range(len(bounds) - 1)))
