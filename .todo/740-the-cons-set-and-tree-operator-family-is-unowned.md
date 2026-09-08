# The cons set / tree operator family has no owner since `.todo/033` closed

Difficulty: Medium

`.todo/033-sequence-and-set-extensions.md` closed **completed** on 2026-08-15, and
`.todo/715`'s ranked table still points at it for these names. It never covered
them, so the whole family is unowned. Measured on the ANSI suite at
`ca06bd9` (2026-09-08, `ansi-test/results/logs/`), counting TEST-level `ERROR`
lines only:

| operator | tests | note |
|---|---:|---|
| `nunion` | 54 | may legally be the non-destructive answer |
| `nset-exclusive-or` | 49 | ditto |
| `nintersection` | 46 | ditto |
| `nset-difference` | 43 | ditto |
| `nsubst` | 29 | ditto |
| `assoc-if-not` | 28 | complement of the existing `assoc-if` |
| `rassoc-if-not` | 24 | ditto |
| `subst-if` | 23 | |
| `nsubst-if` | 22 | |
| `subst-if-not` | 21 | |
| `nsubst-if-not` | 21 | |
| `nbutlast` | 21 | |
| `member-if-not` | 21 | complement of `member-if` |
| `nsublis` | 18 | |
| `get-properties` | 15 | |
| `list-length` | 13 | must answer `nil` on a circular list |
| `tailp` | 10 | |

**~458 tests, and `cons` sits at 56.2%.** Already present and correct, so NOT in
scope: `set-exclusive-or`, `subsetp`, `sublis`, `tree-equal`, `ldiff`, and
`fifth`..`tenth` (the ordinals `.todo/715` used to rank here landed; chapter 32
of the _Practical Common Lisp_ corpus is byte-identical again, `.kb/asdf.md`).

## Why it is cheaper than the row count suggests

CLHS permits every `n`-prefixed operator here to be non-destructive, so
`nunion`/`nintersection`/`nset-difference`/`nset-exclusive-or`/`nsubst*`/`nsublis`
can be plain aliases of the versions that already exist -- the destructive
promise is a licence, not an obligation. `assoc-if-not` / `rassoc-if-not` /
`member-if-not` are the `complement` of operators that exist. That leaves
`nbutlast`, `list-length`, `tailp` and `get-properties` as real new code.

## How to do it

Per CLAUDE.md "Adding a Built-in Function", for each name: `LispNames` +
`PackageRegistry.CL_SYMBOLS`, `Environment.createGlobal`, the `Jvm`/`Wasm`
`ExprCompiler` case (or a `LispMacroExpander` expansion where the shape is an
expansion over existing primitives, which is what the `-if-not` complements
want), `BuiltinFunctionWrappers.WRAPPER_DEFS`, a `ci-spec.yaml` case, and the
`reference/functions/cl.md` row plus per-operator doc pages in both languages.
Re-measure with `ansi-test/measure.sh cons` afterwards.

Found while re-reading the ANSI report for `.todo/715` (2026-09-08).
