# Contributing to Flotilla

Thanks for looking. This is primarily a learning-and-portfolio project, but it is built to
production standards, and contributions are welcome on those terms.

## Getting started

You need Git and a JDK to launch Gradle (any recent release works — Gradle downloads the actual
JDK 25 toolchain itself). Docker is needed from Phase 9 onwards for integration tests.

```bash
./gradlew build
```

That compiles every module, runs static analysis, checks formatting and runs the tests. It is the
same command CI runs, so if it passes locally it will pass there.

Useful tasks:

```bash
./gradlew spotlessApply          # fix formatting instead of just reporting it
./gradlew :flotilla-core:test    # tests for a single module
./gradlew build --scan           # a build scan when something is slow or mysterious
```

## What "done" means

A change is complete when all of the following hold. This list is not bureaucracy: it is what
keeps the project's central claim — that its correctness is demonstrated rather than asserted —
true.

- [ ] `./gradlew build` passes.
- [ ] New behaviour has tests. New behaviour in the consensus core also has simulation coverage.
- [ ] Every new package has a `package-info.java` carrying `@NullMarked`.
- [ ] Affected documentation under `docs/` is updated in the same change, not later.
- [ ] A new ADR exists if a non-obvious decision was made — see `docs/adr/README.md`.
- [ ] `CHANGELOG.md` has an entry under `[Unreleased]`.
- [ ] No `TODO` markers. Open an issue instead.

## Commit messages

[Conventional Commits](https://www.conventionalcommits.org/en/v1.0.0/). The type prefix drives the
generated changelog and the version bump:

```
feat(core): implement log replication with correct commit rules

Explain *why*, and what was traded away. The diff already shows what changed.
```

Types in use: `feat`, `fix`, `perf`, `refactor`, `test`, `docs`, `build`, `ci`, `chore`.
Scopes match module names without the `flotilla-` prefix: `core`, `storage`, `kv`, `server`,
`client`, `cli`, `sim`, `bench`, `transport`.

## Code style

Formatting is not a matter of opinion here — `spotlessApply` decides it. Beyond that:

- **The core stays pure.** `flotilla-core` must not acquire I/O, threads, a wall clock or
  unseeded randomness. An architecture test enforces this. If you think you need an exception,
  the answer is a new port, not an import.
- **No unbounded queues or collections** on any path that a remote peer or client can drive.
- **No mocking frameworks.** Use the in-memory implementations of the real ports, and add a
  contract test that runs against every implementation. See ADR-0007.
- **Ordered collections in anything that must be deterministic** — the consensus core and the
  state machine. A `HashMap` iteration is enough to make two replicas diverge.
- **No comments and no Javadoc.** The only comment in a source file is the SPDX licence header,
  which Spotless applies. Meaning is carried by names, types and tests; reasoning that does not
  fit into those belongs in `docs/` or in an ADR, where it can be read as a whole rather than
  scattered across files.
- Prefer a named type over an explanation, and a test over a claim.

## Reporting a bug in the consensus layer

If a simulation run fails, the seed is in the failure output. Include it — with the seed, the
failure reproduces exactly:

```bash
./gradlew :flotilla-sim:test --tests '*RandomizedRaftTest*' -Dflotilla.sim.seed=8134729
```

That single number is worth more than any amount of log output.

## Code of conduct

By participating you agree to abide by the [Code of Conduct](CODE_OF_CONDUCT.md).
