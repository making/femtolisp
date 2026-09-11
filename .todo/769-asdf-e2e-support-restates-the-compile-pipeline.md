# `AsdfLibraryE2eSupport` restates the compile pass pipeline

Difficulty: Medium

## What is wrong

`src/test/java/am/ik/rontolisp/e2e/AsdfLibraryE2eSupport.java`'s `compileProgram` spells
the compile path's pass order out by hand:

```
LibraryDefunPruner.prune(EnvironmentLibrary.process(UnreadCharLibrary.process(
    UsocketLibrary.process(GrayStreamsLibrary.process(LispPreludeLibrary.process(
        UserMacroExpander.expand(LoadInliner.inline(...)), features)))), backend))
```

Its own comment says it is "mirroring `RontoLispCli`". That is exactly the copy CLAUDE.md
forbids -- *"the pass pipeline itself is `CompileFrontend.expand`, and nothing may restate
it"* -- and exactly the copy that already drifted twice in the two corpus guards: eight
passes behind when a `tokenizer:` case joined `ci-spec.yaml`, ten when that was fixed, and
`VecLibrary` in the wrong POSITION, which no census of pass NAMES could see. Those two
guards were moved behind `src/test/java/am/ik/rontolisp/cli/CorpusFrontend.java`; this third
copy was not.

Every `asdf:load-system` library E2E runs through it (jose, rove, cl-who, cl-mustache,
split-sequence, ...) on the JVM and both WASM backends, so what drifts here is the
coverage for real third-party trees: a pass the CLI applies and this does not means these
tests compile a program no user can build, and the miss shows up as a library failure
rather than as a missing pass.

## What to do

Reach the pipeline through `CorpusFrontend` / `CompileFrontend.expand` instead, as the
corpus guards do. The wrinkle to solve rather than route around: this class needs its own
system path (`systemDir()` + `extraSystemPath()`) for the `.asd` resolution, and it
compiles the SAME program for four backends with different `Features` / `WitExportDirective.Backend`
pairs. `CompileFrontend.expand` already takes a source loader for exactly this reason --
check what it wants before adding a parameter.

## Verification

- the `Asdf*E2eTest` / `JoseE2eTest` / `JoseTestSuiteE2eTest` / `RoveE2eTest` family stays
  green on all four backends (Docker needed for the two WASM legs).
- no `*.process(` chain is left in `src/test/**` outside `CorpusFrontend`.
