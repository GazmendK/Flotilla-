# Checking linearizability

A replicated store is *linearizable* if every operation appears to take effect at a single instant
between the moment it was called and the moment it returned, and the order of those instants
explains every result a client saw. It is the strongest single-object consistency guarantee there
is, and it is what a client of a key-value store implicitly assumes: a read that starts after a
write has been acknowledged sees that write.

Saying a system is linearizable is cheap. This page describes how Flotilla checks it: a checker
written for this project, run against histories recorded from the simulation and from a real
cluster, and itself tested against histories that are known to be wrong.

## What the checker reads

A **history** is a list of operations. Each has a process, an input, a call time, and one of three
outcomes:

| Outcome | Meaning | How the checker treats it |
|---|---|---|
| ok | the client saw a result | must be placed between its call and return, and must produce exactly that result |
| fail | the operation definitely did not happen | removed from the history |
| info | the client does not know — a timeout, a lost connection, a crash | may have happened at any point after its call, **or not at all** |

The third row is the hard one, and the one most homemade checkers get wrong. A request that timed
out did not fail: the leader may have committed it a second before the connection dropped. Treating
it as failed produces false alarms when a later read sees its value; treating it as successful
produces missed violations. It is neither, and the checker has to consider both.

## How it searches

The algorithm is Wing and Gong's, in the form Lowe and later [Porcupine](https://github.com/anishathalye/porcupine)
made practical:

1. Sort every call and every return by time. At equal times a call sorts before a return, which
   treats the two operations as overlapping — the permissive choice, because timestamps from
   different processes cannot order events that close together.
2. Walk the list. The first call whose operation the model accepts in the current state is placed:
   it is removed, together with its return, and the walk starts over from the front.
3. Meeting a return means some operation has returned without being placed, and nothing before it
   can go first. Backtrack: undo the most recently placed operation and try the next candidate.
4. The history is linearizable once every *observed* operation is placed. Operations whose outcome
   is unknown do not have to be: never having happened is one of the things they may have done.

That search is exponential, and three things keep it usable:

- **Partitioning by key.** Linearizability is compositional — a history is linearizable exactly when
  the operations on each key are — so every key is checked on its own. Three thousand operations
  over ten keys become ten searches over three hundred.
- **Memoisation.** Two search paths that have placed the same observed operations and reached the
  same state lead to the same place, and the second is not explored.
- **Subsumption on unknown outcomes.** Among such paths, one that used a *subset* of the unknown
  operations the other used can do everything the other can, because an unknown operation is never
  forced to happen. The path that used more is dropped. Without this, every pending timeout doubles
  the search.

A search that runs out of its time budget reports **UNKNOWN**. It never reports a pass it did not
earn.

## What a violation looks like

```
NOT LINEARIZABLE on key "x"

  process  timeline               operation
  p1       [---------]       1    put x="1" -> nil
  p2         [-]             2    get x -> "1"
  p3             [-]         3    put x="2" -> "1"
  p4                   [-]   X    get x -> nil

The longest order consistent with the model places 3 of 4 operations (numbered above) and leaves the state at "2".
The operation marked X, get x -> nil by p4, cannot be placed after it, and no order the search tried got further.
```

The timeline is scaled by the order of events, not by elapsed time, so overlaps stay visible however
far apart the timestamps are. `p4` read nothing *after* both writes had returned; no order of four
operations explains that.

## How the checker is checked

A checker that only ever says "ok" is indistinguishable from no checker at all, so:

| Test | What it establishes |
|---|---|
| `LinearizabilityCheckerTest` | the textbook cases: stale reads, reads moving backwards in time, two winners of one compare-and-swap, unknown writes that did and did not happen, failed writes that are seen anyway |
| `BruteForceAgreementTest` | on 50,000 small random histories, the checker agrees with a brute-force search over every order |
| `GeneratedHistoryTest` | histories of 3,000 operations produced by a real sequential execution are accepted, and changing a single read to a value nobody wrote is caught every time |

The brute-force comparison earned its place on the first day. The subsumption rule above was
initially paired with the original stopping condition — *every* operation must be placed — and the
two together are wrong: subsumption refuses to place an unknown operation whenever a path without it
has already reached the same state, and the stopping condition then insists on placing it anyway.
The first random history disagreed with brute force. The generated histories had already failed in
the other direction, rejecting a history that was linearizable by construction.

Each safeguard was then switched off in turn: subsumption in the wrong direction, the old stopping
condition, and returns sorted before calls at equal times are each caught by the brute-force
comparison, and removing memoisation makes the large histories run out of time.

The checker lives in `dev.flotilla.linearizability` and depends on nothing but the JDK, which an
architecture test enforces. A checker that shared code with the system it checks could share its
bugs, and would then agree with them.

## Running it against the simulation

The simulation drives clients against a five-node cluster while it crashes nodes, partitions the
network, drops and duplicates messages and takes snapshots. Each client picks a key, writes through a
leader or reads through any node, and records what it saw. Every value written is unique, so a stale
read cannot hide behind a coincidentally equal one. When a client stops waiting — its node crashed,
installed a snapshot over the entry, or took too long — the operation is recorded as unknown, which
is what a real client would have to report.

Events inside one simulation step are timestamped in the order the clients experienced them. With a
single timestamp per step, an acknowledgement and a read issued right after it look simultaneous, and
the checker is right to let the read go first. That hid the first stale read this setup produced.

| Read path | Result |
|---|---|
| ReadIndex, 40 seeds | linearizable, with at least 20 reads and 20 writes completing per run so a pass cannot come from doing nothing |
| Lease reads, 40 seeds | linearizable — in a simulation whose clocks never drift |
| Reading straight from whichever replica is asked | caught on at least eight seeds in ten, which the test requires |

### What it catches, and what it does not

Every safeguard in the read path was switched off in turn and run against both the random workload
and a targeted scenario — the leader cut off in a minority while the majority elects a new one and
accepts a write, and a read then sent to the old leader:

| Safeguard removed | Random workload | Targeted scenario | Unit test |
|---|---|---|---|
| Waiting for the state machine to reach the read index | caught, on most seeds | — | — |
| Confirming leadership before answering | **not caught** | caught | `ReadIndexTest` |
| A lease four election timeouts long | not caught | caught | — |
| Waiting for the new leader's no-op to commit | not caught | not caught | `ReadIndexTest` |
| Refusing votes right after a restart when leases are on | not caught | not caught | `ReadIndexTest` |
| The clock-drift margin on the lease | not caught | not caught | `ReadIndexTest` |

The second row is the one worth dwelling on. A read answered without confirming leadership is the
textbook linearizability bug, and a random fault schedule almost never exposes it here, because
CheckQuorum makes a leader that loses its majority step down within two election timeouts. The window
in which two leaders both believe in themselves is a few ticks wide, and random partitions rarely
land in it. The targeted scenario lands in it on purpose and produces exactly this:

```
NOT LINEARIZABLE on key "deposed"

  process  timeline       operation
  p1       [-]       1    put deposed="fresh" -> nil
  p2           [-]   X    get deposed -> nil
```

The last row is not caught anywhere but its unit test, and cannot be: the margin protects against
clock drift, and the simulation's clocks do not drift. Without drift a lease of exactly one election
timeout is safe, so a test that passed there would prove nothing either way.

## Running it against a real cluster

`RealClusterLinearizabilityIT` starts three real nodes talking gRPC over loopback, with the storage
layer fsyncing as it does in production. Four clients write, compare-and-swap and read on six keys;
two more only read, and start at a follower so their reads are answered there. Two seconds in, the
leader is killed; two seconds later it comes back, the new leader is killed in turn, and it comes back
too. Every client records its operations through `HistoryRecorder`, and the whole history goes
through the checker.

One run completed 1,376 reads and 840 writes with three unknown outcomes, and the checker needed
53 ms. The test refuses to pass on fewer than a hundred of each. A slow Windows runner once managed
only 68 writes in the fixed schedule, so after the last restart the clients now keep going until
150 of each have completed, for at most thirty seconds, instead of the bar being lowered.

Making the server answer linearizable reads from whatever the receiving node has applied fails the
test on each of three runs. The first version of the test, without the two follower readers, passed
with that change: clients that write are redirected to the leader and stay there, and a leader's own
state is almost never stale. A check that only ever reads from the node most likely to be right is a
check that cannot fail.

## Where this sits

- **[Knossos](https://github.com/jepsen-io/knossos)** is Jepsen's original checker, in Clojure. The
  treatment of `info` operations here follows its model.
- **[Porcupine](https://github.com/anishathalye/porcupine)** is the Go checker this implementation's
  search loop is modelled on, including the linked-list walk and the cache.
- **[Jepsen](https://jepsen.io)** is the harness that records histories from real clusters under
  partitions and crashes. `RealClusterLinearizabilityIT` is a small version of the same idea, and
  `HistoryRecorder` records its histories in the same vocabulary.
- **Elle**, also from Jepsen, checks transactional isolation rather than linearizability, which is a
  different question this project does not need to ask.
