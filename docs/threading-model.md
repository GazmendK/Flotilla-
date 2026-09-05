# Threading model

Flotilla runs a node on two threads and a timer. Which work sits on which thread is the whole
subject of this document, because every one of those placements is a correctness decision before
it is a performance one.

## The threads

```
  ticker ──────────┐
  (daemon, timer)  │
                   ▼
  network ──►  EventQueue  ──►  event loop  ──►  ApplyQueue  ──►  apply loop
  clients ──►  (bounded)        owns RaftNode    (bounded)        owns StateMachine
                                owns the log                      completes futures
                                     │
                                     ├──► StableStore.persist + LogStore.sync
                                     ├──► MessageSink.send
                                     └──► published volatile state ──► callers
```

| Thread | Owns | Never touches |
|---|---|---|
| `flotilla-<id>-eventloop` | `RaftNode`, `SegmentedLogStore`, `FileStableStore` | the state machine |
| `flotilla-<id>-apply` | the `StateMachine`, and the applied index | `RaftNode`, the log, the network |
| `flotilla-<id>-ticker` | nothing — it only offers a `Tick` | everything else |

Callers on any other thread read `volatile` fields the two loops publish. Nothing outside the event
loop ever dereferences `RaftNode`, and nothing outside the apply loop ever calls into the state
machine. Both rules are checkable from a field declaration rather than by reasoning about
interleavings.

## Why the log stays on the event loop

The obvious next step after moving apply off the loop is to move persistence off it too. Flotilla
does not, and the reason is in the code rather than in taste.

The core calls `log.append(...)` itself, inside `step()`. `Ready.entriesToPersist()` is a report of
what was appended and still needs an `fsync`, not a to-do list for the caller. So an append happens
on the event loop no matter what, and only the `fsync` could move.

That split is not safe as the store is written. `SegmentedLogStore.sync()` forces
`segments.getLast()`. If a second thread called it while the event loop rotated to a new segment,
the sync would force the **new, empty** segment and leave the full one unsynced — an entry reported
as durable that is not. That is data loss, not a data race, and no amount of `synchronized` on the
method fixes it without also serializing appends behind the `fsync`, which is the thing being
avoided.

So the boundary is stated instead of blurred: **one thread owns the log completely.** The `fsync`
cost is addressed by batching rather than by parallelism, which turns out to be the larger win
anyway.

## Group commit by batching

The event loop takes one event, then drains up to `maxBatchSize - 1` more from the queue before
producing a single `Ready`. A burst of proposals therefore costs one `fsync` between them all
instead of one each.

Measured on the single-node path with `FsyncPolicy.ALWAYS`, 2000 proposals submitted without
waiting:

| | |
|---|---|
| entries persisted | 2001 |
| `fsync` calls | 33 |
| entries per `fsync` | 60 |
| largest batch | 64 (the configured cap) |

`GroupCommitTest` asserts that ratio, and its second test sets `maxBatchSize = 1` to show the
comparison is real: batching disabled, the same workload pays one `fsync` per entry.

Batching does not weaken any ordering rule. Messages still leave only after the hard state and the
log are durable, because that ordering lives inside `processReady()` and a batch produces exactly
one of those.

## Overflow policies

Every queue is bounded, and each kind of work states what happens when its queue is full. An
unbounded queue would only convert a throughput problem into an out-of-memory problem.

| Work | Full-queue policy | Why |
|---|---|---|
| Proposal | rejected with `BackpressureException`, counted | The caller learns now. Buffering would delay the same answer and hide the overload |
| Tick | dropped, counted | A full queue means the loop is busy, and the next tick is already scheduled |
| Inbound message | dropped | The protocol already assumes a lossy network; the sender retries |
| Committed entries → apply | **blocks the event loop** | Applying a committed entry is not optional. Dropping it would break State Machine Safety |

The last row is the only blocking one, and it is deliberate: when the state machine falls behind,
the apply queue fills, the event loop stalls on the handoff, the event queue fills, and proposals
start being rejected. Backpressure propagates outward to the client instead of accumulating
silently inside the node. `BackpressureTest` drives exactly that chain with a state machine that
refuses to return.

## Shutdown

`close()` runs in a fixed order, and each step exists because of a specific failure:

1. cancel the ticker and shut down its executor;
2. ask the event loop to stop, and post a `Shutdown` event so a loop parked in `take()` wakes;
3. join the event loop;
4. stop the apply loop and join it — it drains what is already queued;
5. fail every future still in the registry, then every proposal still in the event queue;
6. close the stable store and the log.

**No thread that owns a file is ever interrupted to stop it.** `FileChannel` implements
`InterruptibleChannel`: interrupting a thread blocked in channel I/O closes the channel for good.
An earlier version of `close()` interrupted the event loop, and under load the node died with
`Cannot write to …wal` — the shutdown itself destroyed the log handle. The `Shutdown` event exists
precisely so the loop can be stopped without that. Interruption remains only as a last resort,
after the join has already timed out and the channel is beyond saving anyway.

Step 5 covers both places a proposal can be waiting. Entries drained into the in-flight batch are
failed too — a batch pulled from the queue is no longer *in* the queue, so an exception mid-batch
would otherwise strand every proposal behind it. That was a real defect in the first version of
batching, found by the shutdown-under-load test.

## What this does not solve

- A slow `fsync` still delays tick processing. Batching bounds how often that happens, not how long
  one takes. On a pathologically slow disk a leader can still miss heartbeat deadlines.
- The state machine must be deterministic and must not block forever. A `StateMachine` that hangs
  is indistinguishable from a hung node — which is what makes it usable as a test instrument.
- None of this is exercised by the deterministic simulation, which drives `RaftNode` directly and
  never constructs a `RaftServer`. The threading model is a delivery mechanism for the same steps,
  not a second implementation of them, and it is covered by ordinary tests instead.
