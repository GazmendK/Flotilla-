# ADR-0011: A leader commits only entries from its own term

- Status: Accepted
- Date: 2026-08-10

## Context

The obvious commit rule is: an entry is committed once a majority of servers store it. It is also
wrong, and Figure 8 of the paper exists to show why.

Consider a leader in term 3 whose log ends with an entry from term 2 that it inherited from a
predecessor. It replicates that entry to a majority and, under the obvious rule, commits it. It
then crashes. The election restriction (§5.4.1) compares logs by **last term first**: a server
whose log is shorter but ends at a higher term is considered more up to date. So a server that
never received the term-2 entry can still win the next election — and it will overwrite it.

An entry that was committed is now gone. This is the one thing Raft must never allow, and the bug
is invisible in any test that does not partition a cluster at exactly the wrong moment.

## Decision

The leader advances `commitIndex` to index `N` only if:

1. a majority of voters report `matchIndex >= N`, **and**
2. `log[N].term == currentTerm`.

Entries from earlier terms are never committed directly. They are committed *transitively*: once
an entry of the current term commits, everything before it is committed with it, because the log
matching property guarantees a majority holds the whole prefix.

Two consequences follow, and both are implemented:

- **A leader appends a no-op entry of its own term immediately on election.** Without it a leader
  that inherits a log has no entry of its own term to commit, so it can never commit anything at
  all — including the entries its predecessor left uncommitted. The no-op is not cosmetic; it is
  what unblocks the term.
- **The same rule is why a linearizable read has to wait for that no-op to commit** (Phase 11): a
  leader does not know its true commit index until then.

## Alternatives considered

**Count replicas regardless of term**, i.e. the obvious rule. Rejected: it loses committed data.
It is kept alive in the test suite instead — `Figure8Test` asserts that the commit index stays at
zero while an entry from an earlier term sits on a majority, and removing the term check from the
implementation makes it fail with `expected: 0L but was: 2L`.

**Have a new leader rewrite inherited entries into its own term.** This would make the simple rule
correct, and some systems do something like it. Rejected: it changes entries that followers have
already stored, which breaks the log matching property that everything else depends on, and it
turns an O(1) operation into one proportional to the uncommitted tail.

## Consequences

- Commit latency after a leader change includes one extra round trip for the no-op. In exchange,
  the transition is safe without any special case.
- `maybeAdvanceLeaderCommit` is ten lines and carries the safety guarantee of the entire system.
  It is worth reading carefully and worth its own test file.
- A leader that never sees a proposal still commits its no-op, so a freshly elected leader reaches
  a known commit index without client traffic.
