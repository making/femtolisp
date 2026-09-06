#!/bin/bash
# (.todo/726) Forward-ms by the 256-minus-64 method on the device arm, -w bf16 against -w f32:
# cd to a directory holding Llama.class (llm.lisp compiled --gpu --simd) and run it; the
# model path is the GB10 box's. Prints rate, the traced token count, and the load time per run.
M=/home/maki/models/qwen35-gguf/Qwen3.5-0.8B-BF16.gguf
for round in 1 2; do
  for w in bf16 f32; do
    for n in 64 256; do
      out=$(LLAMA2_TRACE=1 java --enable-native-access=ALL-UNNAMED --add-modules jdk.incubator.vector -Xmx16g Llama $M -m chat -t 0 -n $n -w $w -i "Tell me a short story about a cat." 2>&1 >/dev/null)
      rate=$(echo "$out" | grep -o 'achieved tok/s: [0-9.]*' | grep -o '[0-9.]*$')
      toks=$(echo "$out" | grep -c '^[0-9]*:[0-9]* ')
      load=$(echo "$out" | grep -o 'in [0-9]* ms' | head -1)
      echo "round=$round w=$w n=$n rate=$rate trace_lines=$toks load=$load"
    done
  done
done
