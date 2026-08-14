# ADR-0010: Roles are types, not a field

- Status: Accepted
- Date: 2026-08-10

## Context

A Raft server is a follower, a candidate or a leader, and each role owns data the others have no
use for. A candidate collects votes. A leader tracks which peers it has heard from, and from Phase
4 onwards the replication progress of each of them. A follower knows who its leader is.

The usual implementation keeps a `role` enum and puts every role's data in the same object, unused
and null in the other two thirds of the lifetime. That shape has a specific failure mode: a leader
field read while the server is a follower returns something stale rather than failing, and the bug
surfaces far from its cause.

## Decision

`RaftState` is a sealed interface with three implementations — `Follower`, `Candidate`, `Leader` —
and the node holds exactly one of them. Role-specific data lives on the role that owns it, and is
unreachable from the others.

Transitions go through `becomeFollower`, `becomeLeader` and `startElection`, which are the only
places that assign the field. Everything that depends on the role reads it through a pattern
switch over the sealed type, so the compiler checks that all three cases are handled.

`RaftRole` stays as a separate enum for the outside world: metrics, logs and the wire protocol need
a flat value, and exposing the state objects there would leak internals.

## Alternatives considered

**An enum plus nullable fields.** Fewer types, and the shape most implementations use. Rejected
because it makes "which fields are meaningful right now" a matter of convention, and because
adding a fourth role — `PRE_CANDIDATE`, which PreVote requires — means auditing every field by
hand.

**Full State pattern with behaviour on the state objects**, each role implementing its own
`handleAppendEntries` and so on. Tempting, and it is what the pattern usually means. Rejected
because Raft's term rules apply uniformly *before* any role-specific handling, so behaviour would
have to be split between a shared preamble and three subclasses. That is harder to follow than one
`step` method with an explicit switch, and it scatters logic that the paper presents together.

## Consequences

- Adding a role is a compile error at every site that switches on the state, which is how the
  fourth one was added safely.
- `Candidate` and `Leader` are mutable, which is unusual for this codebase. They are owned
  exclusively by one `RaftNode`, which is itself only ever entered from one thread, so no sharing
  is possible. `Follower` is a record because it has nothing to mutate.
- Reading the current leader requires a switch rather than a field access. That is three lines in
  one place, in exchange for it being impossible to read a leader that does not exist.
