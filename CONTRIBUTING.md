# Contributing to Block Reality

Block Reality is an early structural-analysis and education project. Independent
verification and reproducible field reports are more valuable right now than expanding
the feature list.

## Good first contributions

- Reproduce one quick-start case on a different launcher or operating system.
- Add an independently derived beam, shell, mechanism or buckling fixture.
- Improve installation diagnostics or documentation without changing mechanics.
- Propose a compact teaching scenario with an expected result.
- Check terminology, units, colour accessibility or the English/Chinese documentation.

For a numerical result that may be wrong, use the
[research feedback form](https://github.com/rocky59487/block-reality/issues/new?template=research-feedback.yml)
before writing code. A small model with a reference value is much easier to review than a
large world save.

## Before opening a pull request

1. Read [the scope in the README](README.md#scope) and
   [the architecture decisions](docs/DECISIONS.md).
2. Open or link an issue for any change to element semantics, support rules, material
   properties, result interpretation or public claims.
3. Keep Minecraft types out of `mod/api` and `mod/core`.
4. Do not make world changes from an analysis preview or failed solve.
5. Keep new numerical claims traceable to a closed form, an independent implementation,
   a published reference or a solver-independent invariant.

A mechanics change is incomplete without a failing regression case written before or
alongside the fix. Update the generated verification record when its source fixtures
change.

## Reproducing a report

Please include:

- Block Reality release or commit SHA
- operating system, Java version, launcher and Forge version
- the smallest structure that reproduces the result
- structural tokens, supports and every applied load
- expected result and its derivation or reference
- `/br status` and the relevant `/br members` or `/br section <id>` output
- screenshots or `logs/latest.log` only after removing unrelated private information

Do not report a real building or safety decision for analysis. Block Reality is not a
building-code checker or a substitute for professional structural engineering.

## Development checks

The authoritative commands and packaging steps are kept in
[`QUICKSTART.md`](QUICKSTART.md) and [`docs/RELEASING.md`](docs/RELEASING.md).
At minimum, run the Java tests for the area changed. Mechanics and release changes must
also run the real sidecar verification path; tests that silently skip the backend do not
count as passing evidence.

## Pull-request style

Keep each pull request narrow. State:

- the observable problem
- the model or invariant that decides correctness
- what changed
- the tests run
- any limitation that remains

By contributing, you agree that your contribution is licensed under the repository's
Apache License 2.0.
