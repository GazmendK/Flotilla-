# ADR-0009: PreVote and CheckQuorum are on by default

- Status: Accepted
- Date: 2026-08-10

## Context

The election protocol in the paper has two availability problems that only appear once a cluster
runs long enough to be partitioned.

**A returning node deposes a healthy leader.** A server cut off from the cluster times out
repeatedly and increments its term on every attempt. It cannot win — nobody hears it — but its
term climbs unbounded. When the partition heals, its first `RequestVote` carries a term far above
everyone else's. The term rules are unconditional: the leader sees a higher term and steps down.
The cluster then holds an election it did not need, and is unavailable for the duration. Nothing
was wrong except that a node came back.

**A leader in the minority does not notice.** A partitioned leader keeps believing it leads. It
cannot commit anything, so safety holds, but it keeps answering as leader and — once lease-based
reads exist — would answer reads from state that has already been superseded.

## Decision

Both mitigations from the dissertation are implemented and **enabled by default**.

**PreVote (§9.6).** Before incrementing its term, a candidate runs a non-binding round asking
whether it *would* be elected. The request advertises the term it would use; the sender's own term
does not move, and neither does any receiver's. Only after a majority says yes does a real
election begin. A partitioned node therefore never inflates its term at all — it cycles as
`PRE_CANDIDATE` at a constant term for as long as the partition lasts.

**CheckQuorum.** A leader tracks which peers it has heard from. Once per election timeout it
checks whether that set is still a majority and steps down if it is not.

CheckQuorum also enables the **leader lease**: a server that currently has a leader and whose
election timer has not elapsed ignores vote requests entirely — *before* applying the term rules.
Ordering matters here. Applying the term rule first and then checking the lease would let the
disruptive term through, which is exactly the case being prevented.

## Alternatives considered

**Leave both off, as the paper describes.** Correct but needlessly fragile in operation: a routine
network blip costs an election. Rejected.

**PreVote only.** Covers the returning-node case but leaves a minority leader believing it leads,
and provides no basis for lease reads later. Rejected.

**CheckQuorum only.** The lease covers most disruption, but only while a leader is known and the
timer has not elapsed. PreVote additionally keeps terms from inflating in the first place, which
also keeps the numbers in logs and metrics meaningful. Rejected as incomplete.

## Consequences

- An extra round trip before every real election. Elections are rare and already cost at least one
  round trip; the added latency is not measurable against the outage it prevents.
- A fourth role, `PRE_CANDIDATE`, that the paper does not name. It appears in metrics and in the
  cluster visualizer, and it is the observable signature of a partitioned node.
- Both flags can be turned off. That is deliberate: `PreVoteTest` and `CheckQuorumTest` each run
  the same scenario with the mechanism disabled and assert the failure it prevents. A safeguard
  nobody has watched fail is a safeguard nobody can trust.
- The lease depends on the election timer, not on wall-clock time, so it inherits the determinism
  of the tick model. This is not yet the clock-dependent lease that lease *reads* would require —
  that assumption is deferred to Phase 11 and will get its own record.
