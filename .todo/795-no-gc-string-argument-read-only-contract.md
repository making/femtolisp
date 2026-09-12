# A `--no-gc` `:string` argument is the module's own memory, and nothing says so

**Status:** open. Found 2026-09-13 verifying
`792` (`623ac390f`), by the filer of that item.

Difficulty: Low

## What happens

`--no-gc` passes a `:string` ARGUMENT as `(ptr+4, [ptr])` of a block the module already
holds -- no staging, no copy. That is the right design and it is most of why the
measurement reactor is 528 bytes there against 1,658 on wasm-GC. What is missing is the
other half of it: the host is now holding a pointer into the module's own data, and
**nothing in `.kb/no-gc-scalar-wasm.md`, `doc/*/guides/wasm-nogc.md`,
`doc/*/guides/wasm-host-boundary.md` or `doc/*/reference/functions/rontolisp-wasm-import.md`
says it must not write through it.**

Identical literals are deduplicated into one block, so the blast radius is not the one
call:

```lisp
(rontolisp:wasm-import 'h :from "env" :as "h" :params '(:string) :returns nil)
(defun once () (h "SECRET"))
(defun twice () (h "SECRET") (h "SECRET"))
(defun other () (h "SECRET"))
```

`--no-gc --no-wasi --optimize=size`, with a JS host that writes six bytes through the
pointer on its first call:

```
Once:                                    host sees ptr 12 = "SECRET"
Twice (host writes on the first call):   host sees ptr 12 = "SECRET"
                                         [host wrote through the pointer]
                                         host sees ptr 12 = "xxxxxx"
Other (a different function):            host sees ptr 12 = "xxxxxx"
```

The literal is corrupted for **every** use in the module, in every function, for the life
of the instance -- which for a reactor is the life of the page. A host author cannot guess
that from "the module hands you a pointer and a length".

## Why this is worth writing down rather than shrugging at

This exact hazard is the stated reason
[`789`](789-string-boundary-drags-in-the-charvec-normalizer.md)'s item 2 was **rejected**
on the wasm-GC backend: passing a literal's own `(ptr,len)` instead of copying it was
measured at ~182 bytes and refused because a host write-back would reach
`StringTable.addString`'s dedup and permanently break every other use of that spelling,
interned symbol names included.

The two decisions are not in conflict -- on `--no-gc` the saving is not 182 bytes but the
shape of the whole backend, and paying a copy per argument would give back what makes it
worth having. But the reasoning now exists in the repo in two places with opposite
conclusions and only one of them written down. A reader who finds `789`'s rejection first
will read `792` as an oversight.

Note the wasm-GC side is unaffected in practice: there a `:string` argument points at
staged scratch that the wrapper pops after the call, so a write-back corrupts a region
already dead. It is `--no-gc` where the pointer is durable and shared.

## What to do

**1. State the contract where a host author reads it.** A `:string`/`:bytes` argument is
borrowed, read-only, valid for the duration of the call, and backed by memory the module
keeps using -- writing through it is undefined and will corrupt every other use of the same
literal. `doc/en|ja/guides/wasm-nogc.md` and `wasm-host-boundary.md` (mirrored, same file
set, byte-identical fences per CLAUDE.md), plus the sentence in
`.kb/no-gc-scalar-wasm.md` that currently stops at "no staging, no copy".

**2. Say the same thing about the wasm-GC side, with its different reason.** There the
pointer is transient rather than shared: it stops being valid when the wrapper returns, and
a host that keeps it past the call reads whatever the next call staged. Both halves belong
in `wasm-host-boundary.md`, because the question a host author has is the same one and the
answers differ by backend.

**3. Cross-reference the trade in `789`'s item-2 rejection** so the two conclusions read as
one decision made twice with different numbers, not as an inconsistency.

**Not proposed: copying the argument on `--no-gc`.** That is the win. If a checked mode is
ever wanted, it belongs behind a debug flag that copies and compares after the call, not in
the default lowering.

## Touch points

- `doc/en/guides/wasm-nogc.md` + `doc/ja/guides/wasm-nogc.md`
- `doc/en/guides/wasm-host-boundary.md` + `doc/ja/guides/wasm-host-boundary.md`
- `doc/en|ja/reference/functions/rontolisp-wasm-import.md`
- `.kb/no-gc-scalar-wasm.md` (the `:string` ARGUMENT sentence), `.kb/wasm-import.md`
- `DocExamplesTest` if any added example is executable
