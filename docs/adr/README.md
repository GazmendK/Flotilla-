# Architecture Decision Records

Every non-obvious decision in this project is written down here, in the form of a short record
that states the context, the decision, the alternatives that were considered, and the
consequences that were accepted.

## Why

Code shows *what* a system does. It rarely shows *why* it does it that way, which alternative was
rejected, or what was knowingly traded away. Six months later that reasoning is gone, and the
usual result is that someone "fixes" a deliberate decision.

An ADR is cheap to write and answers the only question that matters when changing something:
*was this on purpose?*

## Rules

- One decision per record. If a record needs the word "and" in its title, it is two records.
- Records are immutable once accepted. A decision that no longer holds is not edited: a new
  record supersedes it, and the old one is marked `Superseded by ADR-XXXX`.
- Numbers are never reused.
- Write the alternatives honestly, including the one you nearly picked. A record that makes the
  chosen option look obvious is usually hiding the interesting part.
- Keep it short. One page is plenty; if it needs more, the design doc belongs in `docs/`.

## Format

We use a light [MADR](https://adr.github.io/madr/) variant:

```markdown
# ADR-0000: Title in imperative mood

- Status: Proposed | Accepted | Superseded by ADR-XXXX
- Date: YYYY-MM-DD

## Context
## Decision
## Alternatives considered
## Consequences
```

## Index

| ADR | Title | Status |
|---|---|---|
| [0001](0001-record-architecture-decisions.md) | Record architecture decisions | Accepted |
| [0002](0002-java-25-as-baseline.md) | Use Java 25 as the baseline runtime | Accepted |
| [0003](0003-gradle-over-maven.md) | Use Gradle with convention plugins instead of Maven | Accepted |
| [0004](0004-pure-deterministic-core.md) | Keep the Raft core pure and deterministic | Accepted |
| [0005](0005-three-module-layout.md) | Three modules, with internal layering enforced by tests | Accepted |
