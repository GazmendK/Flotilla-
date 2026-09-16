# ADR-0025: Whole snapshots in the core, chunking at the edge

- Status: Accepted
- Date: 2026-09-16

## Context

Once a leader compacts its log, a follower that fell behind the compacted prefix can no longer be
caught up by `AppendEntries`: the entries it is missing no longer exist. Raft §7 answers this with
`InstallSnapshot`, and Figure 13 describes it as a chunked RPC — `offset`, `data`, `done` — because
a snapshot can be far larger than a message.

That shape is awkward for a deterministic core. A chunked transfer is a small protocol of its own:
partial state per peer, a temp file on the receiver, resumption after a dropped chunk, a decision
about what happens when a new snapshot supersedes one that is half delivered. Modelling all of it
inside `RaftNode` would put buffering, file handling and progress tracking into the one class that
has to stay a pure function of its inputs.

There is a second question underneath: who owns the snapshot? The core needs to *read* the newest
snapshot to send it. The runtime needs to *write* it durably, restore the state machine from it, and
delete the old ones. Those are different jobs with different failure modes.

## Decision

**The core speaks in whole snapshots. Chunking belongs to the transport.**

`InstallSnapshotRequest` carries a `Snapshot` — last included index, last included term, the cluster
configuration and the payload — and nothing about offsets. The gRPC layer splits one of these into
chunks on the way out and reassembles them on the way in; `offset` and `done` stay in the schema,
where the transport uses them. The core sees a snapshot appear whole or not at all, which is exactly
the property that makes the install rules simple enough to state:

- `lastIncludedIndex <= commitIndex` — stale. Refused, and the reply carries the follower's own
  commit index so the leader learns where to continue instead of sending it again.
- The follower's log already holds a matching entry at `lastIncludedIndex` — the snapshot describes a
  prefix it already has. The log is kept, `commitIndex` moves up, and the state machine is not
  touched. This is Raft §7's retained-suffix case, and it is the cheap path.
- Otherwise the log cannot be reconciled with the snapshot and is replaced wholesale
  (`LogStore.resetTo`), `commitIndex` and the emitted apply position jump to the snapshot point, and
  the snapshot is handed to the runtime through `Ready.snapshotToInstall`.

**`SnapshotStore` is a read port.** It has one method, `latest()`. The core never writes a snapshot:
taking one is the runtime's job, and installing a received one is handed back through `Ready` so the
runtime can make it durable *before* the response promising it goes out — the same ordering that
already applies to log entries, which is why `Ready.requiresSync()` is true when a snapshot is
present.

**The response carries a match index, not a byte count.** `InstallSnapshotResponse.matchIndex` is
the index through which the follower's log now agrees with the leader, whichever of the three paths
it took. The leader treats it exactly like the match index in an append reply, which is what lets
one code path handle all three outcomes.

**Compaction is guarded in the core.** `RaftNode.compactLog(index)` refuses unless `latest()` already
covers `index` and the entries have been handed to the state machine. Compacting entries no snapshot
covers is the failure that cannot be repaired: the follower needing them can never be caught up
again, by appends or by snapshot.

**A peer being sent a snapshot is tracked, and the wait is bounded.** `Progress` gains a `SNAPSHOT`
state holding the index in flight. While it holds, that peer is sent no further appends and no
second snapshot — resending the whole state on every proposal would drown the follower the transfer
is meant to rescue. A lost reply would otherwise strand the peer there forever, so the state expires
after `snapshotTimeoutTicks` and the transfer starts again. The timeout must be at least one
election timeout; retrying sooner competes with a transfer that may still be running.

## Alternatives considered

**Model chunking in the core, as Figure 13 describes.** Faithful to the paper. Rejected: it moves
buffering and partial state into the class that the deterministic simulation exists to exercise, and
the simulation would then be testing a message splitter rather than the consensus rules.

**Let the core write to the snapshot store.** Fewer moving parts — `RaftNode` could save a received
snapshot itself. Rejected because the store is durable: a file write, an fsync and an atomic rename
would happen on the single-writer event loop, which is the one thread that must not block. Handing
the snapshot out through `Ready` keeps the write where every other durable write already is.

**Send the snapshot in the leader's response to a rejected append.** No separate message type.
Rejected because the two have different sizes by orders of magnitude and different flow control; the
inflight window that governs appends is meaningless for a transfer that may take seconds.

## Consequences

- `flotilla-core` can be tested against snapshots without any I/O: `InMemorySnapshotStore` and
  `InMemoryLogStore` are enough to drive every rule above.
- The transport now owns a stateful concern it did not have before — reassembling chunks per peer,
  and discarding a partial transfer when a newer one starts. That is where the tests for it live.
- A snapshot carries the cluster configuration from the moment it was taken. Nothing reads it yet;
  membership is static until Phase 12, and a node restored from a snapshot takes its members from
  its configuration. Carrying it now means Phase 12 does not have to change the wire format or the
  on-disk snapshot to get it.
