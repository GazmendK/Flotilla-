# Deterministic simulation

The consensus core performs no I/O, starts no threads, never reads a clock and never uses unseeded
randomness. That constraint exists for exactly one reason: it lets an entire cluster run inside a
single thread, on virtual time, as a pure function of a seed.

A run that takes ten minutes of simulated cluster time takes milliseconds of real time, and a
failure found after millions of events replays exactly.

## What is simulated

| Aspect | Model |
|---|---|
| Time | A virtual clock in abstract units; 1000 units is one Raft tick. Nothing ever sleeps. |
| Message delivery | A priority queue ordered by (arrival time, sequence). Latency is drawn per message. |
| Latency | Uniform in a configured range, with a configurable chance of a very slow link. |
| Loss | Each message is dropped with a configured probability. |
| Duplication | Each message may be delivered twice, with independent latencies. |
| Reordering | Falls out of per-message latency; no extra mechanism is needed. |
| Partitions | Nodes are assigned to one of two sides; only same-side traffic is delivered. Delivery is re-checked on arrival, so a partition can form while a message is in flight. |
| Crashes | Every byte not yet synced is discarded and the node is rebuilt from its persisted state, exactly as `kill -9` would leave it. |
| Restarts | The rebuilt node starts as a follower at its persisted term. |
| Client load | Proposals are offered to a randomly chosen leader with a configured probability. |

Persistence is modelled honestly rather than optimistically. A node's log tracks a *synced*
watermark; the runtime only advances it when the `Ready` cycle says the entries are durable, and a
crash truncates everything above it. The same ordering the real runtime must obey — persist
before sending — is what the simulation enforces.

## What is checked

Every one of the five safety properties from Figure 3 of the paper is checked after **every**
step, plus two properties the paper leaves implicit.

| Invariant | Statement |
|---|---|
| Election Safety | At most one leader per term. |
| Leader Append-Only | A leader never overwrites or deletes an entry in its own log. |
| Log Matching | If two logs hold the same index with the same term, their prefixes are identical. |
| Leader Completeness | An entry committed by some term is present in every leader of a higher term. |
| State Machine Safety | No two nodes apply different entries at the same index, and entries are applied exactly once and in order. |
| Monotonic Progress | Term and commit index never move backwards within a process lifetime, and the commit index never exceeds the log. |

Log Matching is O(nodes² × log length) and runs every few ticks rather than every step; the rest
run continuously.

The checkers are themselves tested. `InvariantTest` feeds each one a hand-built world that
violates exactly its property and asserts it fires — because a checker that only ever says "ok" is
indistinguishable from no checker at all.

## Reproducing a failure

Every failure prints the seed:

```
Leader Completeness violated: n5 leads term 12 but holds LogEntry[term=8, index=32] where
committed index 32 is LogEntry[term=1, index=32], committed by term 11

Seed: 77
Reproduce by re-running the failing test with a single seed:
  ./gradlew :flotilla-testing:test --tests '*AdversarialTest*' -Dflotilla.sim.seed=77

Recent events:
t=31200 n3 -> CANDIDATE term=11
t=31640 n3 -> LEADER term=11
...
```

That single number is the whole reproduction. There is no such thing as a flaky failure here —
only a failure nobody has diagnosed yet.

Knobs, all forwarded to the test JVM by the build:

| Property | Meaning |
|---|---|
| `flotilla.sim.seed` | Run exactly this seed instead of a range |
| `flotilla.sim.seeds` | How many seeds to run |
| `flotilla.sim.offset` | Start the seed range at an offset, used by the nightly job |
| `flotilla.sim.ticks` | How long each run lasts |

## What the simulation is good at, and what it is not

This section matters more than the rest of the page.

**Randomized testing only finds what its fault model can produce.** That is not a slogan here; it
was measured. The Figure 8 defect — a leader committing an entry from an earlier term by counting
replicas — was deliberately injected into the commit rule and the simulation was run against it:

| Configuration | Seeds | Result |
|---|---|---|
| `chaotic(5)`, 1200 ticks | 500 | **not detected** |
| `adversarial(5)`, 800 ticks | 300 | detected at seed 77 |

The two configurations differ in one decisive knob: the adversarial one replicates a single entry
per `AppendEntries`. With the default batch size the leader's own no-op travels together with the
inherited entries, so the quorum frontier almost never rests on an entry from an older term, and
the buggy code path produces the same outcome as the correct one. The window exists — it is just
too narrow for a uniformly random search to hit within five hundred runs.

Two conclusions follow, and both are load-bearing for this project:

1. **The hand-written `Figure8Test` is not redundant.** It constructs the exact interleaving in
   three message exchanges and fails immediately when the term check is removed. Targeted tests
   and randomized search are complements, not substitutes; the targeted test knows where to look,
   the search finds what nobody thought to look for.
2. **A fault model is a design artifact and deserves the same scrutiny as the code.** The
   `adversarial` profile exists because a fault model that never narrows batches cannot exercise
   the states where batching matters.

Other known limits, stated rather than discovered later:

- **Crashes happen at step boundaries**, not between two individual writes. Finer-grained crash
  points arrive with the fault-injecting file layer in Phase 6, where they belong.
- **No Byzantine behaviour.** Nodes may vanish, but never lie. That is the project's failure model.
- **No clock skew**, because the core has no clock to skew. This becomes relevant only when
  lease-based reads arrive in Phase 11, and will be modelled then.
- **Liveness is checked coarsely**: after the faults stop and every node is running, a leader must
  exist and the cluster must commit. There is no bound on how fast that happens.

## Bugs found by the simulation

Kept as a running list, because the honest measure of a test suite is what it has caught.

| Date | Finding |
|---|---|
| 2026-08-10 | The first `LeaderCompleteness` checker was too strict: it demanded that *every* current leader hold every committed entry, including a stale leader in a lower term that had been partitioned away. The paper only requires it of leaders in higher terms. The simulation was right to fail; the checker was wrong. |
| 2026-08-10 | Verified, by injection, that removing the current-term condition from the commit rule is caught — but only under the adversarial profile. See the section above. |
