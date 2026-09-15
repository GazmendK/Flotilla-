# ADR-0018: Apply moves off the event loop; the log does not

- Status: Accepted
- Date: 2026-09-05

## Context

[ADR-0017](0017-single-writer-event-loop.md) put one thread in charge of `RaftNode` and left two
things on that thread that do not belong to consensus: persisting to disk, and applying committed
entries to the state machine. Both are unbounded in duration and neither is under the node's
control — an `fsync` waits on hardware, and a state machine is user code.

The symptom was visible in the tests: a state machine that refused to return stopped the node from
committing anything at all, because the apply call sat between `Ready` and `advance()`. Consensus
had been made hostage to application code.

The plan was to move both off the loop. Only one of them can be.

## Decision

**Applying moves to its own thread. The log stays on the event loop, and the `fsync` cost is
addressed by batching instead.**

Committed entries are handed to a bounded queue that a second thread drains; that thread owns the
state machine outright and is also what completes proposal futures. Consensus now proceeds while
the state machine is stuck, which `ApplyLoopTest` asserts directly: the commit index advances past
50 entries while the applied index has not moved.

The log does **not** move, for a reason that is in the code rather than in preference. The core
calls `log.append(...)` itself inside `step()`, so an append happens on the event loop regardless;
only the `fsync` could migrate. But `SegmentedLogStore.sync()` forces `segments.getLast()`, and a
sync issued from another thread while the loop rotated segments would force the new, empty segment
and leave the full one unsynced. That is an entry reported durable that is not — data loss, not a
data race. Making it safe means serializing appends behind the `fsync`, which removes the benefit
that motivated the move.

So one thread owns the log completely, and the `fsync` count is reduced by **group commit through
batching**: the loop drains up to `maxBatchSize` events before producing a single `Ready`. Measured
on 2000 proposals with `FsyncPolicy.BATCHED`, that is 33 physical `fsync` calls for 2001 entries —
60 entries per sync. (See the amendment below: the first version of this measurement counted the
wrong thing.) Batching does not weaken any ordering rule, because the "persist, then send" sequence
lives inside one `Ready` and a batch produces exactly one of those.

## Alternatives considered

**A storage thread with a sync token.** The event loop would capture which segments and positions
must be forced, and hand that to a writer thread. This is correct and is roughly what etcd's async
storage writes do. Rejected for this change as disproportionate: it requires reworking segment
ownership, and batching already removes most of the cost it would buy. It is the natural next step
if a benchmark ever shows the loop stalling on `fsync`.

**Moving appends out of the core, etcd-style.** Have `Ready.entriesToPersist()` be a to-do list the
caller executes, with the core holding entries in an unstable buffer. Cleanest of all, and it would
make the storage thread trivial. Rejected because it changes the core's contract with `LogStore`,
which the deterministic simulation and every storage contract test are built on. Not a change to
make as a side effect of a threading decision.

**Dropping committed entries when the apply queue is full.** Would keep the event loop free. Also
breaks State Machine Safety, which is the one thing that must never bend. The handoff blocks
instead, and that block is the mechanism by which backpressure reaches the client.

## Consequences

- The apply queue is the only queue in the node with a blocking overflow policy. When the state
  machine falls behind, the loop stalls on the handoff, the event queue fills, and proposals are
  rejected. The chain is deliberate and documented in
  [`docs/threading-model.md`](../threading-model.md).
- A proposal's future is now completed by the apply thread. `ProposalRegistry` became a
  `ConcurrentSkipListMap` because the event loop registers and the apply thread completes.
- A slow `fsync` still delays ticks. Batching bounds how often, not how long.
- **This change surfaced two real defects.** Stopping the event loop by interrupting its thread
  destroyed the log: `FileChannel` is an `InterruptibleChannel`, so an interrupt during a write
  closes the channel permanently and the node died with `Cannot write to …wal`. Shutdown now posts
  a `Shutdown` event and only interrupts as a last resort. Separately, batching pulls events into a
  local list, so an exception mid-batch stranded every proposal behind it — futures that were
  neither in the queue nor in the registry, waiting forever. The batch is now swept on the way out.
  Both were found by the shutdown-under-load test, and both were invisible before batching existed.

## Amendment — 2026-09-15: the measurement counted calls, not fsyncs

The original measurement used `FsyncPolicy.ALWAYS` and counted how often the event loop called
`log.sync()`. Under `ALWAYS`, though, the segmented log forces the file after **every append**, and
the consensus core appends each proposal the moment it steps it — before the event loop has produced
a `Ready`, let alone batched one. Every proposal paid its own physical `fsync`; the loop's single
`sync()` per batch came on top. Group commit was real in the counter and absent on the disk.

It surfaced as a timeout, not as a wrong number: on a CI runner with a slow disk, 2000 proposals
took longer than thirty seconds, which thirty-three fsyncs never would.

The fix is a policy, not a code path. `FsyncPolicy.BATCHED` — now the default — forces the log only
when `sync()` is called, while the hard state is still forced on every write. The store counts
physical forces, `GroupCommitTest` asserts on that count, and a third test pins down the failure
mode: under `ALWAYS`, two hundred proposals cost at least two hundred forces. Re-measured on the
same workload with `BATCHED`, the number is again 33 for 2001 entries — this time on the disk.
