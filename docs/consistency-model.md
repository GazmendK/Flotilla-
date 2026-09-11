# Consistency model

What Flotilla promises, what it does not, and where the boundary is drawn on purpose. Statements
here are the ones the tests hold; anything a phase has not reached yet is marked.

## The guarantees

**Writes are linearizable.** A committed entry has been durably stored on a majority, and every
replica applies the log in the same order. Once a write returns, every subsequent operation sees it
or something later — there is a single point in time between the call and the return at which it
took effect.

**Reads are linearizable, by going through the log.** A `Get` is an ordinary log entry today. That
is correct and slow: a read costs a full round of consensus. `ReadIndex` and lease reads in Phase 11
make it cheap without weakening the guarantee.

**Execution is exactly-once per session.** A request carries `(clientId, sequence)`. The state
machine remembers the last sequence and the last response per client, so a retry after a lost
acknowledgement returns the stored response instead of running again. Without this a Raft key-value
store is only *at-least-once*, and `SessionDedupTest` shows what that costs: a retried
compare-and-swap re-executes, sees its own earlier write, and tells the caller it lost a race it
actually won.

**The state machine is deterministic.** The same log produces the same state and byte-identical
snapshots on every replica. It reads no clock, draws no random numbers, and iterates nothing
hash-ordered.

**Sessions and the applied index are part of a snapshot.** A restored replica does not re-run
requests it had already answered, and does not re-apply entries it had already applied.

## The boundaries

**No multi-key atomicity.** Each command touches one key. There is no transaction spanning several
keys, and no way to make two writes visible together. `CompareAndSwap` is the only conditional
primitive, and it is single-key by construction.

**No snapshot isolation and no multi-version reads.** A `Scan` observes the state at the moment it
is applied. It is not a consistent view across a longer sequence of reads, and there is no way to
pin a revision. A watch API with MVCC revisions is listed under "after 1.0" for exactly this reason.

**No causal consistency across sessions.** Ordering guarantees hold per client session. Two clients
that coordinate outside the store — one writes, then tells the other over a side channel — are only
protected by the linearizability of the individual operations, which is enough for
read-after-write, but there is no session-to-session causal token.

**Exactly-once has a lifetime, and it is stated rather than hidden.** A session expires after
`sessionTimeoutEntries` log entries without activity. A retry arriving after that is answered with
`UNKNOWN_SESSION` — refused, never silently re-executed. The guarantee therefore reads: *exactly
once, or an explicit refusal.* It never degrades quietly into at-least-once.

**A sequence number that has been passed cannot be retried.** Only the last response per client is
cached, which is what keeps the table bounded. An older sequence gets `STALE_SEQUENCE`, again
explicit. A client is expected to have one request in flight per session, as in the thesis.

**Anonymous requests are at-least-once.** A request with no session is not deduplicated. This exists
for internal and idempotent operations, and `SessionDedupTest` keeps a case demonstrating the
double execution — the contrast is what makes the sessioned path worth its cost.

## Why sessions expire on the log and not on a clock

A wall clock is not replicated. If a session expired after "five minutes", each replica would
measure those five minutes from the moment *it* applied the entry, and two replicas would disagree
about whether a session still exists. Disagreement about the session table is disagreement about
whether a request executes — a divergence of the replicated state itself.

Expiry is therefore counted in log entries, which every replica sees identically. The same rule
applies to anything else time-dependent that the store may grow later: the leader writes its
timestamp *into the entry*, and every replica uses that replicated value. See
[ADR-0021](adr/0021-no-wall-clock-in-the-state-machine.md).

## What a client must do

1. `Register` once, and keep the returned `clientId`.
2. Number requests from 1 upward, one in flight at a time.
3. On a timeout or a lost connection, **retry the identical request** — same `clientId`, same
   `sequence`, same command. That is what makes the retry safe.
4. On `UNKNOWN_SESSION`, register again and re-issue from sequence 1. The previous request may or
   may not have taken effect; the store cannot say, and neither can the client without reading.

Step 4 is the honest edge. It is reachable only by a client that was silent longer than the session
timeout, and it is reported rather than papered over.
