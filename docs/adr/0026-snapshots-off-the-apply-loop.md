# ADR-0026: Snapshots are taken off the apply loop, compaction is asked for on the event loop

- Status: Accepted
- Date: 2026-09-16

## Context

A snapshot has to describe the state machine at one exact index. The obvious way to get that is to
stop applying, serialize everything, and start again. For a state machine holding two hundred
thousand keys that is tens of milliseconds during which nothing commits — and the entries still
arriving pile up behind it.

Moving the work to the event loop is worse. The event loop sends heartbeats, and a heartbeat that
arrives late enough is an election. [ADR-0018](0018-apply-off-the-loop-fsync-on-it.md) already
separated those two threads for exactly this reason.

But compaction cannot move anywhere. Discarding the compacted prefix mutates the log and the Raft
node's view of where its log begins, and only the event loop may touch either.

So the work does not belong to one thread. It belongs to three.

## Decision

**Each thread does the part it is allowed to do.**

| Step | Thread | What it costs |
|---|---|---|
| `StateMachine.capture()` — freeze a consistent view | apply loop | 200k keys: 15 ms, pointer copies only |
| serialize, write a temp file, fsync, rename, fsync the directory | snapshot worker | 200k keys: 29 ms of encoding, plus the disk |
| `RaftNode.compactLog(index)` | event loop, as a `Compact` event | a rename and two fsyncs |

`capture()` returns something that can be serialized later and is unaffected by everything applied
afterwards. `KvStateMachine` implements it by copying the map and the session table — every key and
value is immutable, so only the tree nodes are new. The default implementation in `StateMachine`
serializes immediately, which is correct but pauses for the whole cost; a state machine that cares
overrides it.

**One snapshot at a time.** A trigger that arrives while one is running is counted and dropped, not
queued. Two concurrent snapshots would double the memory for no benefit, since the second would
supersede the first anyway.

**The policy asks, it does not command.** A snapshot is due after a number of entries or a number of
bytes since the last one, whichever comes first. Both are needed: ten thousand tiny entries are not
worth a snapshot, and ten large ones can be.

**Compaction is a request, and it can be refused.** By the time the `Compact` event reaches the event
loop the log may already have been compacted further — a snapshot installed from a leader does that.
The event is then skipped rather than failing. `compactLog` itself refuses outright unless the
snapshot store already covers the index, which is the one mistake that cannot be repaired
afterwards: entries nobody holds and no snapshot covers are gone.

**An incoming snapshot is written on the event loop.** It arrives in a `Ready`, and the response
that says "installed" must not go out before the bytes are durable — the same rule that governs log
entries. That is the one place the event loop deliberately blocks on I/O, bounded by the size of the
snapshot.

## Alternatives considered

**Serialize on the apply loop.** One thread, no coordination, no `capture()`. Rejected on the
measurement: it is the 15 ms *plus* the 29 ms, and the 29 ms grows with the payload rather than the
key count.

**A persistent sorted map, so that a capture is one reference assignment.** This is the right answer
and it is not built. The JDK has no persistent sorted map, and writing one is a project of its own.
What is built removes two thirds of the pause; the remaining third is O(entries) and the number is
in `docs/threading-model.md` rather than hidden.

**`fork()`, the way Redis does it.** Copy-on-write at the page level, essentially free. Not
available to a JVM process.

**Snapshot on a timer instead of on progress.** Simpler to reason about. Rejected because an idle
cluster would keep writing identical snapshots, and a busy one would still outrun the timer.

## Consequences

- The apply pause is a number, measured by a test that fails if copying ever stops being the cheap
  half of the work.
- A snapshot is never guaranteed at a particular index. Nothing depends on one existing at a given
  moment; compaction simply does not happen until one does.
- `RaftServer` exposes the snapshot count, the compaction count, the restore count and the longest
  apply pause, so the next phase has something to put on a dashboard.
