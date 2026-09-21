# ADR-0029: Membership changes one server at a time, in force from the moment they are appended

- Status: Accepted
- Date: 2026-09-21

## Context

A cluster that cannot change its members has to be rebuilt to replace a failed machine. Changing
members while the cluster runs is where Raft is easiest to get wrong: a configuration change alters
what a majority *is*, and two nodes that disagree about the configuration can each believe they hold
one.

The dissertation offers two mechanisms. **Joint consensus** (§4.3) moves through an intermediate
configuration in which decisions need a majority of the old *and* of the new members, and can change
any number of members at once. **Single-server changes** (§4.1) add or remove exactly one voter per
step; any majority of the old configuration then overlaps any majority of the new one, so no
intermediate state is needed.

## Decision

**One server per change.** `ConfChange` is `AddLearner`, `Promote` or `Remove`, each touching one
node. A configuration is stored as a log entry holding the whole new configuration, not a delta, so
the configuration a node is in is simply the last one in its log.

**A configuration is in force the moment it is appended, not when it commits.** A leader counts the
new majority as soon as it has written the entry; a follower as soon as it has stored it. When a
follower's log is truncated past a configuration entry, the configuration it carried is dropped
with it, and the node falls back to the last one still in its log. This is §4.1, and it is the
counter-intuitive part: waiting for the commit would let a leader decide with a majority that is no
longer the one the rest of the cluster uses.

**At most one change in flight.** A change is refused while the previous configuration entry has
not committed. Two uncommitted single-server changes compose into a two-server change, and two-server
changes can produce disjoint majorities.

**No change before the leader has committed an entry of its own term.** This is the published
correction to the dissertation (Ongaro, raft-dev, 2015). Without it, a leader elected in a new term
can append a configuration change while a change from a previous term is still uncommitted in some
logs — two changes in flight after all, just across a leader change.

**Unsafe requests are refused with the arithmetic in the message.** Removing the last voter is
impossible; promoting a learner that has not caught up is refused; and a change is refused if the
voters of the resulting configuration that the leader has heard from within an election timeout
would not form a majority of it. An operator removing the node that is actually down is doing the
right thing; removing a healthy one while another is down would stop the cluster, and the leader
says so instead.

**A leader may remove itself.** It keeps leading until the removal commits — counting only the new
voters, not itself — then steps down, and a node outside the configuration never campaigns.

**Learners and non-members do not count.** Only voters of the current configuration count towards a
commit, a vote, a read confirmation or CheckQuorum. A vote granted by a learner is ignored.

**The configuration survives compaction.** A snapshot carries the configuration in force at its
index, and compacting the log moves that configuration into the node's base, so a later snapshot —
taken when no configuration entry is left in the log at all — still carries it.
`MembershipTest` has a case for exactly that: it fails if compaction forgets.

## Alternatives considered

**Joint consensus.** Can replace a whole cluster in one step and is the only option for changes that
cannot be broken into single steps. Rejected because every change an operator actually makes — add a
node, remove a node, replace a node — is a sequence of single steps anyway, and joint consensus
doubles the states the protocol can be in. It stays on the roadmap for bulk changes.

**Apply the configuration when it commits.** Simpler to reason about locally, and the classic
mistake. Rejected for the reason above.

**Allow a change before the no-op commits.** Saves one round trip after an election. Rejected: that
round trip is what the errata costs, and it is cheap.

## Consequences

- `RaftNode` no longer has a fixed membership. The configuration passed to its constructor is only a
  starting point: the log, or the snapshot under it, overrides it.
- A node that is not in its starting configuration may run; it simply never campaigns until a
  configuration that makes it a voter reaches its log. That is how a new node joins.
- Every guard above is tested with itself switched off; each makes a test in `MembershipTest` fail.

## Amendment — 2026-09-21: a removed node is not done until its removal commits

"A node outside the configuration never campaigns" was too strong. The membership simulation found
a cluster of two in which the leader appended its own removal and lost leadership before the other
node received the entry. The removed node, no longer a voter in its latest configuration, never
campaigned; the other node still counted it as a voter, needed its vote, and could not get it with
the shorter log. Nobody could lead again.

The rule is now the dissertation's (§4.2.2): a node that is a voter in the configuration before its
latest one, while that latest one has not committed, still campaigns — and does not count its own
vote, since it is not a voter of the configuration the election is decided in. Once the removal
commits, it stops for good. `MembershipTest` reproduces the deadlock directly, and fails both when
the rule is removed and when the node counts its own vote.
