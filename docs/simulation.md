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
step, plus three properties the paper leaves implicit.

| Invariant | Statement |
|---|---|
| Election Safety | At most one leader per term. |
| Leader Append-Only | A leader never overwrites or deletes an entry in its own log. |
| Log Matching | If two logs hold the same index with the same term, their prefixes are identical. |
| Leader Completeness | An entry committed by some term is present in every leader of a higher term. |
| State Machine Safety | No two nodes apply different entries at the same index, and entries are applied exactly once and in order. |
| Monotonic Progress | Term and commit index never move backwards within a process lifetime, and the commit index never exceeds the log. |
| One Change At A Time | Consecutive configurations in any log differ in at most one server, and no leader holds more than one uncommitted configuration. |

Log Matching is O(nodes² × log length) and runs every few ticks rather than every step; the rest
run continuously.

The checkers are themselves tested. `InvariantTest` feeds each one a hand-built world that
violates exactly its property and asserts it fires — because a checker that only ever says "ok" is
indistinguishable from no checker at all.


## Compaction, and why the invariants had to change

Nodes snapshot their applied state and compact their logs while the simulation runs, and a node
that falls behind a compacted prefix is caught up by `InstallSnapshot`. Logs therefore no longer
start at index 1, and three of the checkers compared logs **positionally** — the first entry of one
log against the first entry of another. That is correct only while every log starts at the same
place. Against a compacted log it is wrong in both directions: Monotonic Progress reports a false
violation the moment the commit index passes the number of entries left, and Log Matching quietly
stops comparing the entries that matter. All three now address entries by index, and `InvariantTest`
holds a hand-built world for each case, including a compacted log checked against a full one.

The simulated state machine folds every entry it applies into a digest, and a snapshot carries that
digest. Two replicas that have applied through the same index must hold the same digest — which is
what makes a snapshot transfer *checkable* rather than merely observable. Making a snapshot carry
the wrong state, or letting recovery skip the log replay that follows a restore, breaks the
simulation within twenty seeds.

Two things happening in the same step cost an afternoon: a node can install a snapshot and crash
before the step ends, and the per-step view cannot order those two events. The checker now treats a
step in which a node restarted or installed a snapshot as one where its applied position moved for a
reason it can see but cannot order, re-reads the position from the node, and resumes checking order
from the next step. The content checks never stop.

## Membership changes

`MembershipChaosTest` starts three voters and two spare nodes that belong to no configuration, and
while the usual faults run it keeps changing the cluster: a random leader is asked to add a spare as
a learner, promote a learner, remove any member — itself included — or hand leadership to another
voter. Most requests are refused, for the reasons the core gives, and the refusal must say why.
After every step, a leader that is not a voter in its own configuration must have an uncommitted
change that removed it. When the faults stop, the cluster must elect a leader, commit its last
configuration, and bring every voter to the same configuration and commit index. Each run makes
twenty to forty accepted changes; one that makes fewer than five fails, so a pass cannot come from
doing nothing. The default sixty seeds pass, and so did a thousand more run once.

Each guard in the core was then switched off in turn and the sixty seeds run against it:

| Guard removed | Seeds failing | How |
|---|---|---|
| A removed leader steps down once its removal commits | 48 of 60 | the per-step check on leaders |
| Followers adopt a configuration when they append it | 46 of 60 | One Change At A Time, voters disagreeing, Leader Completeness, Election Safety |
| A snapshot carries the configuration at its index | 36 of 60 | Leader Completeness, Election Safety |
| Only one change in flight | 16 of 60 | One Change At A Time; with that checker also removed, **2 of 60 still lose a committed entry** |
| A removed node campaigns until its removal commits | 3 of 60 | no leader once the faults stop |
| An installed snapshot replaces the configuration | 2 of 60 | Leader Completeness |
| No change before an entry of the leader's own term commits | **0 of 60** | only `MembershipTest` |
| A truncated configuration entry takes its configuration with it | **0 of 60** | only `MembershipTest` |
| Votes from outside the configuration are ignored | **0 of 60** | only `MembershipTest` |

The fourth row is the reason the rule exists: with two changes in flight and no checker looking for
them, the simulation produces two majorities that do not overlap, and a committed entry disappears.
The last three rows are the honest part. The errata case needs a leader to be elected with an older
configuration change still uncommitted in its log and to start a new one at once, and random faults
rarely line that up; the other two need a configuration entry to be overwritten, or a removed node's
vote to decide an election, at exactly the wrong moment. Each has a targeted test that fails the
moment its guard is removed — the same division of labour as with Figure 8, described below.

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
| 2026-09-16 | Enabling compaction made three checkers fail: they compared logs by position, which stops meaning anything once a log starts above index 1. Then `StateMachineSafety` fired on a node that had installed a snapshot and crashed in the same step — a gap in the checker's model of the world, not in the code. Both are described above. |
| 2026-09-21 | `MembershipChaosTest` failed on nineteen seeds of twenty with a gap in the applied entries. The driver changes the configuration *between* steps, and in a single-voter cluster the change commits and is applied at once — but the list of entries applied in a step was cleared at the start of the next one, before any checker had seen it. It is now cleared after the checkers run. A harness bug, not a Raft bug. |
| 2026-09-21 | The twentieth seed found a real one: a leader of two nodes appended its own removal, lost leadership before the other node received the entry, and the cluster could never elect anyone again. The removed node did not campaign, because it was no longer a voter in its latest configuration; the other needed its vote and could not get it with a shorter log. The dissertation (§4.2.2) says what to do: a node removed by a configuration that has not committed yet still campaigns, without counting its own vote. `MembershipTest` now reproduces the deadlock in a few lines. |
| 2026-09-16 | With one timestamp per step, a write acknowledged and a read sent right after it looked simultaneous to the linearizability checker, which correctly let the read go first — and so a stale read passed. A harness bug, found by switching off leadership confirmation in the read path and watching nothing fail. |
