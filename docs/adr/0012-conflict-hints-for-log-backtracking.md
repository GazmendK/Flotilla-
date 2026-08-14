# ADR-0012: Followers return conflict hints instead of being probed one entry at a time

- Status: Accepted
- Date: 2026-08-10

## Context

When a follower rejects an `AppendEntries` because the entry at `prevLogIndex` does not match, the
leader has to find the point where the two logs still agree. The paper's description is to
decrement `nextIndex` and retry.

That is correct and, in the cases the paper cares about, cheap — divergence is usually a few
entries. It is not cheap in the cases operations actually hits: a follower that was partitioned
during a burst of writes, or one that accepted a long uncommitted suffix from a deposed leader,
can diverge by hundreds of thousands of entries. One decrement per round trip means one round trip
per entry, and a follower that should rejoin in a second takes minutes.

## Decision

A rejecting follower includes two hints:

- `conflictTerm` — the term of the entry it actually holds at `prevLogIndex`, or 0 if its log is
  simply too short to have one;
- `conflictIndex` — the first index of that conflicting term, or its own `lastIndex + 1` when the
  log is too short.

The leader uses them as follows: if it holds any entry of `conflictTerm`, it jumps `nextIndex` to
one past the last such entry, because everything up to there is known to agree in term. Otherwise
that whole term is absent from the leader's log, so it skips the entire run of it and continues
from `conflictIndex`.

Divergence is therefore skipped one *term* at a time rather than one entry at a time. Since a term
change requires an election, the number of terms in any real log is small.

Term 0 is used as the "no conflicting term" sentinel. That is unambiguous: terms start at 1, so no
entry ever carries term 0, and index 0 with term 0 is already the established representation of
"before the beginning of the log".

## Alternatives considered

**Plain decrement, as in the paper.** Simplest, and it is what a first implementation should do.
Rejected once the cost was made visible: `ConflictBacktrackingTest` resynchronizes a follower that
diverged by a thousand entries and asserts it takes fewer than a hundred `AppendEntries` messages.
With plain decrement that number is a thousand.

**Binary search over the log.** Fewer round trips in the worst case, and no extra fields on the
wire. Rejected: it needs state per peer across several round trips, and the term-based jump is
already effectively constant for realistic logs.

**Sending the follower's whole log summary.** Rejected as disproportionate — the message grows
with log size to solve a problem two integers solve.

## Consequences

- Two extra fields on `AppendEntriesResponse`, both zero on success.
- The leader-side lookup walks its log backwards from the end. It terminates early once it sees a
  term below `conflictTerm`, because terms increase monotonically along a log.
- The follower-side lookup walks back from `prevLogIndex` while the term is unchanged. Both are
  bounded by the length of one term's run, not by the length of the log.
- Correctness does not depend on the hints. They only change how fast `nextIndex` converges; a
  leader that ignored them entirely would still be safe, just slow. That makes the optimization
  safe to change later without re-reasoning about consensus.
