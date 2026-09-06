# ADR-0019: Commands are encoded by hand, not by a schema compiler

- Status: Accepted
- Date: 2026-09-06

## Context

The replicated state machine needs a byte format for commands, responses and snapshots. Protobuf is
the obvious choice, arrives in Phase 9 for the network anyway, and would be less code.

Two properties made it the wrong choice *here*.

**A log entry outlives the code that wrote it.** A command encoded today is read back after a
restart in two years, by a binary built from a different commit. The format is therefore a
compatibility contract with the past, not a wire convenience — the same argument
[ADR-0014](0014-custom-write-ahead-log.md) made for the log itself.

**Snapshots are compared byte for byte.** Two replicas that reached the same state must produce
identical snapshot bytes, because that is how divergence is detected. Protobuf does not guarantee
canonical encoding: field ordering, `optional` presence handling and map iteration order are all
implementation choices, and Google's own documentation says serialization is not deterministic
across languages or versions. Building a divergence detector on a non-canonical encoding is
building it on sand.

## Decision

`CommandCodec` and `KvSnapshotCodec` write the format explicitly, in the same style as
`RecordCodec`: a type byte, then big-endian length-prefixed fields, with an explicit
present/absent byte for optional values.

Three rules make it canonical:

- **Fields are written in declaration order, always, with no presence-based omission.** An absent
  optional writes one zero byte rather than nothing.
- **Snapshots iterate a `TreeMap`.** The state machine's map is ordered by key, so its serialization
  is a function of its contents and not of the route taken to them.
- **Decoding is total.** Arbitrary bytes produce a `MalformedCommandException` and nothing else —
  never a `BufferUnderflowException`, never a huge allocation from a hostile length. Lengths are
  bounded before anything is allocated, and trailing bytes are refused rather than ignored.

`flotilla-node` therefore still has **zero runtime dependencies**. Protobuf will arrive in Phase 9
for the RPC layer, where its evolution rules are genuinely useful and where canonical encoding is
not required.

## Alternatives considered

**Protobuf for everything.** Less code and one format instead of two. Rejected on the canonical
encoding point above: the determinism tests compare snapshot bytes, and that comparison is only
meaningful if the encoding is a function of the value.

**Java serialization.** Not seriously. It is not portable across versions, not canonical, and a
deserialization gadget surface on data that arrives over a network.

**Protobuf for commands, hand-written for snapshots.** Half the code with most of the risk removed.
Rejected because a command is stored *in the log* and shares the same longevity problem, and
because two formats to reason about is worse than one.

## Consequences

- Every new command type means touching the codec, its size calculation, and a round-trip test.
  That is deliberate friction on a format that is hard to change later.
- The size of an encoding is computed before the buffer is allocated, so encoding writes exactly as
  many bytes as it reserved. A mismatch fails loudly at encode time rather than producing a short
  record.
- The determinism guarantee is testable rather than assumed. Replacing the state machine's
  `TreeMap` with a `HashMap` makes three of the four `DeterminismTest` cases fail — including the
  one that matters most in practice, where a replica restores a snapshot and catches up while
  another replays the whole log.
- One `DeterminismTest` case survives that substitution: feeding two fresh replicas the same log.
  It is kept anyway, because it documents the weakest form of the property and shows why the other
  three are needed.
