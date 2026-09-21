# ADR-0030: A learner is promoted after a quick round, and leadership is handed over, not fought over

- Status: Accepted
- Date: 2026-09-21

## Context

[ADR-0029](0029-single-server-membership-changes.md) lets an operator add a learner and promote it.
Two questions were left open.

**When is a learner ready to vote?** A new voter raises the quorum the moment its configuration is
appended. If it is still far behind, it contributes nothing to that quorum until it catches up, and a
three-node cluster that becomes four with one member lagging tolerates no failure at all for as long
as the catch-up takes. "Has it replicated everything?" is the wrong test: a learner that is always
exactly one batch behind a busy leader will never pass it, and one that just caught up after an hour
on a slow link passes it and may then fall behind again at once.

**How does a leader step aside?** Taking a leader out for maintenance, or removing it, used to mean
stopping it and waiting an election timeout for someone else to notice. With CheckQuorum and the
leader lease, the other voters actively ignore a candidate while they are still hearing from the
leader — which is exactly what they should do against a disruptive node, and exactly wrong when the
leader itself wants to hand over.

## Decision

**Catch-up is measured in rounds** (dissertation §4.2.1). The leader tracks each learner in rounds:
a round ends when the learner has acknowledged every entry the leader had when the round began, and
the next begins at once with the leader's log as it is then. A learner counts as caught up when its
last round took no longer than an election timeout *and* the round in progress has not already run
longer than that. The second condition matters: a learner that has every entry and then goes silent
completes no more rounds, and without it would look caught up forever. `RaftNode.catchUpStatus`
exposes the rounds, so a refused promotion can say how long the last one took.

The first version also required the learner to hold every committed entry. That check was removed
again: under steady load a learner is always a few entries behind the commit index, so the check
would refuse a healthy learner indefinitely — the very problem rounds exist to avoid. It was found
because switching it off made no test fail, and asking why showed it should not be there.

**Leadership is transferred with `TimeoutNow`** (dissertation §3.10). `transferLeadership(target)`
accepts any voter other than the leader. The leader stops accepting proposals and configuration
changes, brings the target up to date, and then sends it `TimeoutNow`. The target starts a real
election immediately — no PreVote, since every voter heard from the leader a moment ago and would
refuse one — and marks its vote requests as a leadership transfer, which voters let past the leader
lease. A follower acts on `TimeoutNow` only if it comes from the leader it knows.

If the transfer has not completed within an election timeout — the target is down, cut off, or too
far behind — the leader abandons it and accepts work again. It does not step down: a transfer that
fails must leave the cluster as available as it was.

**A leader that has sent `TimeoutNow` gives up its lease for the rest of its term.** A lease is safe
because the voters that acknowledged the leader promised not to vote for anyone else for an election
timeout. A vote request marked as a transfer breaks that promise by design. The leader cannot know
when its `TimeoutNow` will arrive — it may be delayed past the point where the leader abandoned the
transfer — so from the moment it sends one, lease reads fall back to ReadIndex, which confirms
leadership with a majority each time and does not depend on anyone's promise.

## Alternatives considered

**Promote when `matchIndex` reaches the leader's last index.** Simple, and wrong in both directions,
as described above. Rejected.

**Promote automatically when a learner catches up.** etcd leaves promotion to the operator; so does
this. An automatic promotion is a configuration change nobody asked for at that moment.

**Keep the lease through a transfer and trust the timeout.** Abandoning the transfer after an
election timeout and resuming lease reads looks safe until the `TimeoutNow` arrives late. The
`LeaderTransferTest` case for this holds the message back, lets the transfer be abandoned, delivers
it, has the new leader commit a write, and shows the old leader would have answered a lease read
with the value from before it.

**Let the target run PreVote first.** PreVote asks "would you vote for me?", and every voter would
say no, because the leader is alive. The transfer exists precisely to override that.

## Consequences

- Proposals are refused for up to an election timeout while a transfer is under way. Clients see a
  not-leader error and retry with backoff.
- A transfer to a target that never catches up costs one election timeout of refused writes, and
  nothing else.
- Lease reads on a leader that has attempted a transfer cost a round trip for the rest of its term.
- Every guard here was switched off in turn, and each makes a test fail. Two needed a second attempt.
  The first version of the late-`TimeoutNow` test passed without the lease being given up: the leader
  resends `TimeoutNow` on every reply from a caught-up target, so the transfer had quietly completed
  during the test and the held-back message never mattered. And a round target that stops moving
  with the leader's log can only be shown in a unit test of the tracker, because in the test cluster
  a learner always catches up within a single delivery.
