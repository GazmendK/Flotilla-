# Consistency model

What Flotilla promises, what it does not, and where the boundary is drawn on purpose. Statements
here are the ones the tests hold; anything a phase has not reached yet is marked.

## The guarantees

**Writes are linearizable.** A committed entry has been durably stored on a majority, and every
replica applies the log in the same order. Once a write returns, every subsequent operation sees it
or something later — there is a single point in time between the call and the return at which it
took effect.

**Reads are linearizable by default, without going through the log.** A read carries a consistency
level, chosen per request:

| Level | How it is answered | Guarantee |
|---|---|---|
| `LINEARIZABLE` (default) | ReadIndex: the leader confirms with a majority, after the read arrived, that it still leads; the answering node waits until it has applied that far | linearizable, under exactly the assumptions Raft already makes |
| `LEASE` | the leader answers at once if a majority acknowledged it recently enough | linearizable **only if** no node's clock runs slower than another's by more than `clockDriftBoundTicks` over one election timeout; off unless `RaftConfig.leaseReads` is set |
| `STALE` | whatever the receiving node has applied | none — a lagging follower answers with old data, and `ReadPathTest` shows it doing so |

A linearizable read may be sent to a follower. The follower asks the leader for the read index,
waits for its own state machine to catch up, and answers locally. That spreads the work of answering
across replicas; every linearizable read still costs the leader one confirmation round. A read through the log is still possible by sending `Get` as a command,
and costs what it always did.

A new leader answers no linearizable read until it has committed an entry of its own term, because
until then it does not know how far its predecessor committed. With leases on, a node that has just
started refuses to vote for one election timeout, because a follower that crashed has forgotten the
lease it granted. [ADR-0028](adr/0028-readindex-by-default-leases-by-choice.md) records the reasoning.

These are not claims on trust. Histories recorded from the simulation and from a three-node cluster
with its leader killed are checked for linearizability; see [linearizability.md](linearizability.md).

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

`FlotillaClient` does all of this; the steps are listed for anyone writing a client of their own.

1. `Register` once, and keep the returned `clientId`.
2. Number requests from 1 upward, one in flight at a time.
3. On a timeout or a lost connection, **retry the identical request** — same `clientId`, same
   `sequence`, same command. That is what makes the retry safe.
4. On `UNKNOWN_SESSION`, ask whether any earlier attempt of this request could have reached the log.
   If none could — the very first attempt was refused — register again and re-issue from sequence 1.
   If one could, **do not re-issue**: the request may or may not have taken effect, and running it
   under a new session is exactly the double execution sessions exist to prevent. Report it as
   indeterminate.

Step 4 is the honest edge. It is reachable only by a client that was silent longer than the session
timeout, and it is reported rather than papered over — `FlotillaClient` throws
`IndeterminateResultException` and records the operation as `INFO`.
