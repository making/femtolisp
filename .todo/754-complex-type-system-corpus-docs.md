# 754. Complex numbers: type system, corpus, docs

Difficulty: Medium

Depends on 751-753 (all four backends answer complex arithmetic). Spike filed
2026-09-09; SBCL contract lives in `.todo/751-complex-core-value-reader-interpreter.md`.

## Constraints found by the spike

- `ci-spec.yaml` is the single source of truth for `CiSpecE2eTest`: cases share
  global state and run IN ORDER (driver concatenates into one program, slices
  output per case). New `complex` cases go at a position that cannot perturb
  earlier cases, and each case must be backend-deterministic (float-complex
  printing must be byte-identical across the four backends -- verify before
  pinning; `--simd` runs every case twice, so complex output must also be
  representation-stable under `--simd`).
- `./mvnw test` SKIPS `CiSpecE2eTest` (needs `-Drontolisp.binary`) and
  `ExamplesE2eTest`; both must be run explicitly per AGENTS.md ("Native Image E2E",
  "Examples Suite"), each slice foreground with `timeout`.
- Docs: every change mirrored across `doc/en/**` and `doc/ja/**` in the same
  commit (same files/headings, byte-identical fences); per-operator page +
  `_catalog.yaml` entry + package function-page row (AGENTS.md recipe step 7);
  `docs-tool/` tests run separately (`./mvnw -f docs-tool/pom.xml test`).
  `DocExamplesTest` verifies fenced examples (`-Drontolisp.doc.fix=true` to
  rewrite shown results, then plain verify).
- `format` body-layout: if any new operator takes a BODY, add an `IndentRules`
  entry (recipe step 8; unlikely for complex, but check).
- `.todo/037-number-extensions.md` "Complex numbers" section: mark shipped and
  point at 751-754 (it is the only pre-existing item claiming this area).

## Work

1. Type integration: `typep`/`typecase`/`etypecase`/`check-type` arms for
   `complex`/`real` (751 owns the predicates; this owns the specifier mapping --
   coordinate with `.todo/035-type-system.md` if it is still open), `coerce`
   to/from `complex`, `upgraded-complex-part-type` (SBCL: integer -> RATIONAL),
   `type-of` answering `(COMPLEX <part-type>)`-shaped specifiers where the
   existing convention allows.
2. Trans-function audit: every function that can now SEE a complex value gets a
   decision -- implemented (751-753), catchable type-error (ordering: `< > <= >=`,
   `minusp`/`plusp`, per SBCL probes), or real-only-by-contract (`isqrt`,
   `floor`/`ceiling`/`truncate`/`round`, `mod`/`rem`, `gcd`/`lcm`, bitwise ops).
   Each decision pinned by a test on all four backends.
3. `ci-spec.yaml` cases for the full contract (reader, canonicalization,
   contagion, predicates, `sqrt` of negatives, `abs`/`phase`/`conjugate`,
   ordering-errors-catchable). Native E2E run per AGENTS.md before push.
4. Docs (EN+JA): per-operator pages for `complex`, `complexp`, `realpart`,
   `imagpart`, `conjugate`, `phase` (+ `realp` if shipped here rather than 751),
   catalog entries, package-page rows, `DocExamplesTest` green.
5. Close-out: update 037, record history rows on deletion (two commits per
   deletion: remove file, then history row -- CLAUDE.md "Todo management").

## Done when

- `ci-spec.yaml` complex cases green on all four backends x (`--simd`, default)
  via the native E2E leg.
- `DocExamplesTest` + `docs-tool` tests green; EN+JA file sets identical in
  structure.
- Commit trailers use the model name from commit `6fced3b2` (`muse-spark-1.3-contributor`;
  full trailer `Co-Authored-By: muse-spark-1.3-contributor
  <opencode-agent[bot]@users.noreply.github.com>`).
