# ADR-0028: ReadIndex by default, leases only by choice

- Status: Accepted
- Date: 2026-09-16

## Context

A read that goes through the log is linearizable and expensive: it costs an append, a replication
round and an fsync on a majority, for an operation that changes nothing. The dissertation describes
two cheaper ways to answer a read without writing, and they make different promises.

**ReadIndex** (§6.4) records the commit index when the read arrives, confirms with a majority that it
is still the leader, waits for the state machine to reach the recorded index, and reads locally. It is
linearizable under exactly the assumptions Raft already makes.

**Lease reads** (§6.4.1) skip the confirmation: if a majority acknowledged the leader recently
enough, no other leader can have been elected yet, so the leader answers at once. That is only true
if clocks behave. A follower that has not heard from a leader waits one election timeout before it
will vote for anyone else — measured on the follower's clock. If the leader's clock runs slow
relative to its followers', it believes its lease is still valid after they have moved on.

## Decision

**ReadIndex is the default read path, and it is built on rounds.** Every heartbeat the leader sends
opens a new round, every append carries the current one, and a follower echoes the round in its
reply. A read that arrives at round *R* is released only once a majority has acknowledged a round
of at least *R*. An acknowledgement of something sent *before* the read proves nothing: the follower
may have recognised this leader before another one was elected.

**A new leader serves no read until it has committed an entry of its own term.** Until its no-op
commits, a new leader does not know the true commit index — entries committed by its predecessor
may not be marked committed in its log yet.

**Reads batch.** While one round is in flight, further reads wait and share the next round, so a
burst of reads costs two rounds rather than one per read.

**A follower forwards the read.** It asks the leader for a read index, waits until it has applied
that far itself, and answers locally. The leader does the confirmation; the follower does the work.

**Lease reads exist and are off.** `RaftConfig.leaseReads` enables them. The lease lasts
`electionTimeoutMinTicks - clockDriftBoundTicks`, counted from when the acknowledged round was
*sent*, not when the reply arrived. Enabling leases also requires CheckQuorum, because the lease rests
on followers refusing to vote while they still hear from a leader, and that refusal is part of it.

**With leases on, a freshly started node refuses to vote for one election timeout.** A follower that
crashes forgets that it recently heard from a leader. Without this rule it could vote for a new
leader immediately after restarting — while the old leader still believes its lease is valid, and
answers reads from a state that is no longer current.

## Alternatives considered

**Leases by default.** Faster, and what many systems ship. Rejected because the failure mode is
silent: a stale read produced by clock drift looks exactly like a correct one, and nothing in the
system can notice. A default should not depend on an assumption the system cannot check.

**Confirm leadership by counting any reply received after the read.** Simpler than rounds. Rejected:
a reply received after the read may answer a heartbeat sent long before it, and `ReadIndexTest`
shows the difference — with that rule an acknowledgement from before the read releases it.

**Reads through the log.** Correct with no further reasoning at all. Rejected on cost, and kept
available: a client that wants the strongest possible guarantee with the simplest possible argument
can still send a read as a command.

## Consequences

- Every `AppendEntries` request and response carries a round number. The field is additive on the
  wire.
- Linearizable reads cost one heartbeat round each batch, no disk writes, and no log growth.
- Enabling lease reads is an explicit statement about the deployment's clocks: that no node's ticks run
  slower than another's by more than `clockDriftBoundTicks` over one election timeout.
