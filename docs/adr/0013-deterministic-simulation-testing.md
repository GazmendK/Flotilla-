# ADR-0013: Test consensus with deterministic simulation

- Status: Accepted
- Date: 2026-08-10

## Context

The failures that matter in a consensus implementation need an unlucky interleaving to appear: a
leader that crashes after replicating but before responding, a partition that heals at exactly the
wrong moment, a follower whose disk lags by one message. Those are the failures that lose
committed data, and they share two properties that make ordinary testing useless against them.

They cannot be enumerated by hand, because the interesting ones are the ones nobody thought of.
And in a conventional implementation they cannot be reproduced either: the outcome depends on
thread scheduling and real timing, so the same input produces a different run on a loaded machine
than on a quiet one. A failure seen once in CI is, in practice, a failure nobody will see again.

## Decision

Correctness under adversarial conditions is established by **deterministic simulation**: entire
clusters run in one thread on virtual time, driven by a seed, with the safety properties checked
after every step.

This is what the purity of `flotilla-core` was for. Because the core reads no clock, starts no
threads and draws no unseeded randomness, a whole run is a pure function of its seed. The
simulation supplies a virtual clock, a virtual network that drops, delays, duplicates, reorders
and partitions, and stores that lose everything unsynced on crash — through the same ports the
production runtime implements.

Three consequences are deliberate:

- **Speed.** Nothing sleeps, so simulated minutes cost milliseconds and hundreds of randomized
  runs fit in a normal CI job.
- **Reproducibility.** A failure prints its seed. Re-running that seed reproduces it exactly.
- **It exercises the shipped code.** The simulation drives the real consensus implementation
  through the real ports, not a separate model that could drift from it.

## Alternatives considered

**Integration tests over real processes with injected faults.** Realistic, and they catch things a
simulation cannot — process startup, real sockets, real file semantics. Rejected as the *primary*
mechanism because they are slow, and because their failures are not reproducible, which is the
property that makes a failure worth having. They arrive in Phase 9 as a complement.

**A model in TLA+, checked exhaustively.** Genuinely stronger for the state space it covers, and
on the roadmap for the membership protocol. Rejected as the primary mechanism because a model can
drift from the implementation, and because the model is what gets verified, not the code that
ships.

**Randomized tests over the existing in-memory harness**, without virtual time. This is roughly
what `TestCluster` already is. Rejected because without virtual time and crash semantics it cannot
model latency, reordering or lost unsynced state, which is where the interesting failures live.

## Consequences

- Any nondeterminism anywhere in the core silently destroys the value of this approach. That is
  why the architecture test forbids hash-ordered collections, wall-clock reads and unseeded
  randomness, and why `DeterminismTest` replays seeds and compares the full world state.
- **The fault model is now a design artifact.** A randomized search finds only what its faults can
  produce. This was measured rather than assumed: an injected Figure 8 defect survived 500 seeds
  under the default profile and was caught at seed 77 under a profile that replicates one entry
  per message. The default profile was not wrong — it was blind in one direction, and the fix was
  a second profile, not more seeds.
- Hand-written tests for known-hard cases stay. `Figure8Test` finds in three message exchanges
  what the search needed a tailored fault profile to reach.
- Every invariant is a stateful observer that accumulates history, so a run's memory grows with
  its length. Trace output is a bounded ring buffer for the same reason.
