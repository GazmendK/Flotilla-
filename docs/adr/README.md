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
| [0006](0006-tick-based-logical-time.md) | Time enters the core as logical ticks | Accepted |
| [0007](0007-value-types-and-primitives.md) | Wrapper types where they prevent bugs, primitives where they do not | Accepted |
| [0008](0008-no-mocking-policy.md) | No mocking framework; fakes plus contract tests instead | Accepted |
| [0009](0009-prevote-and-checkquorum-by-default.md) | PreVote and CheckQuorum are on by default | Accepted |
| [0010](0010-state-pattern-for-roles.md) | Roles are types, not a field | Accepted |
| [0011](0011-commit-only-current-term-entries.md) | A leader commits only entries from its own term | Accepted |
| [0012](0012-conflict-hints-for-log-backtracking.md) | Followers return conflict hints instead of being probed one entry at a time | Accepted |
| [0013](0013-deterministic-simulation-testing.md) | Test consensus with deterministic simulation | Accepted |
| [0014](0014-custom-write-ahead-log.md) | Write the log format instead of embedding a storage engine | Accepted |
| [0015](0015-hardstate-double-buffering.md) | Store the hard state in two alternating slots | Accepted |
| [0016](0016-crash-at-every-write.md) | Prove durability by crashing at every write | Accepted |
| [0017](0017-single-writer-event-loop.md) | One thread owns the Raft state | Accepted |
| [0018](0018-apply-off-the-loop-fsync-on-it.md) | Apply moves off the event loop; the log does not | Accepted |
| [0019](0019-hand-written-command-codec.md) | Commands are encoded by hand, not by a schema compiler | Accepted |
| [0020](0020-client-sessions-for-exactly-once.md) | Client sessions, because Raft alone is only at-least-once | Accepted |
| [0021](0021-no-wall-clock-in-the-state-machine.md) | The state machine never reads a clock | Accepted |
| [0022](0022-peer-transport-over-grpc.md) | Peers talk over gRPC through one one-way envelope | Accepted |
| [0023](0023-wire-types-stay-at-the-edge.md) | Wire types stay at the edge | Accepted |
| [0024](0024-client-library-and-error-model.md) | A client that retries safely and admits what it does not know | Accepted |
| [0025](0025-whole-snapshots-in-the-core.md) | Whole snapshots in the core, chunking at the edge | Accepted |
| [0026](0026-snapshots-off-the-apply-loop.md) | Snapshots are taken off the apply loop, compaction is asked for on the event loop | Accepted |
| [0027](0027-a-linearizability-checker-of-our-own.md) | A linearizability checker of our own, checked against brute force | Accepted |
| [0028](0028-readindex-by-default-leases-by-choice.md) | ReadIndex by default, leases only by choice | Accepted |
| [0029](0029-single-server-membership-changes.md) | Membership changes one server at a time, in force from the moment they are appended | Accepted |
| [0030](0030-catch-up-rounds-and-leadership-transfer.md) | A learner is promoted after a quick round, and leadership is handed over, not fought over | Accepted |
