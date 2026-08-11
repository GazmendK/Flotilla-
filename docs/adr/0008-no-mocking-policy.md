# ADR-0008: No mocking framework; fakes plus contract tests instead

- Status: Accepted
- Date: 2026-08-05

## Context

The core talks to the outside world through three ports: `LogStore`, `StableStore` and
`RandomSource`. The reflex in a Java project is to mock them.

A mock encodes what the author *believes* the dependency does. In a distributed storage system
that belief is exactly what tends to be wrong. A mocked `StableStore` returns instantly and never
fails; the real one takes milliseconds, can throw when the disk is full, and — most importantly —
can return successfully while the data is still only in the page cache. A test suite built on
mocks passes cheerfully while the system loses committed data on power failure.

## Decision

No mocking framework is on the test classpath. Instead:

1. **Real in-memory implementations.** `InMemoryLogStore` is not a test double; it is the log the
   simulation runs on, and it enforces the same invariants as the durable one (contiguous
   appends, no truncation into a compacted prefix).
2. **Contract tests.** The behaviour of a port is specified once, as an abstract test that every
   implementation must pass. The in-memory and the file-backed log store run the same suite, so
   "the simulation uses the same semantics as production" is checked rather than assumed.
3. **Fault injection instead of stubbed failures.** Where a failure needs to be exercised — a
   crash mid-write, a torn record, a full disk — it is injected at the lowest layer, so the code
   under test experiences it the way it would in reality.

## Alternatives considered

**Mockito for the ports.** Faster to write, and fine for verifying that a collaborator was called.
Rejected because the interesting questions here are about ordering and durability, which a mock
cannot express: "was `persist` called before the message was sent, and had it actually synced" is
a property of the real implementation, not of the interaction.

**Mockito for a few narrow cases**, with fakes elsewhere. Rejected because a partial ban is not a
policy. Once the dependency is on the classpath, the next test under time pressure uses it.

## Consequences

- Writing the first test for a port costs more, because a working fake has to exist. That fake
  then serves every later test, and the simulation, and the contract suite.
- Test failures point at behaviour rather than at interactions, so they survive refactoring: a
  test that asserts "the entry is durable before the acknowledgement" keeps working when the
  internals change, whereas `verify(store).persist(any())` does not.
- The absence of a mocking framework is enforced by the absence of the dependency, and any
  reviewer can see it in `gradle/libs.versions.toml`.
