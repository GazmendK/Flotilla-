# ADR-0027: A linearizability checker of our own, checked against brute force

- Status: Accepted
- Date: 2026-09-16

## Context

The simulation checks Raft's safety properties: one leader per term, committed entries never lost,
replicas applying the same entries in the same order. None of those is what a client relies on. A
client relies on *linearizability* — that a read started after an acknowledged write sees it — and a
system can satisfy every Raft invariant and still violate that, for instance by answering reads from
a leader that has quietly been deposed.

Checking linearizability means searching for an order of operations that explains every observed
result. The established tools are Knossos (Clojure, part of Jepsen) and Porcupine (Go). Neither runs
on the JVM without a second toolchain in the build, and neither can be driven from inside a
deterministic simulation step by step.

## Decision

**Write the checker here, in `dev.flotilla.linearizability`, following Porcupine's search.** Wing and
Gong's backtracking search over a linked list of calls and returns, memoised on the set of placed
operations and the model state, over a history partitioned by key.

**Unknown outcomes are first-class.** An operation whose client timed out may have happened at any
point after its call, or never. The search stops once every *observed* operation is placed, and a
search path that used a subset of the unknown operations another path used, reaching the same state
with the same observed operations, subsumes it.

**Running out of time is UNKNOWN, never a pass.** `CheckResult.isLinearizable()` is true only for a
completed search that found an order.

**The checker depends on nothing but the JDK.** An architecture test enforces it. A checker that
shares code with the system under test can share its bugs.

**The checker is tested against a brute-force search.** On 50,000 small random histories — valid and
invalid, with and without unknown outcomes — both must agree.

## Alternatives considered

**Use Knossos or Porcupine.** Mature and battle-tested. Rejected because either would add a second
language runtime to the build, and because driving it from the simulation would mean serialising
histories across a process boundary on every run.

**Check only invariants that are cheaper than linearizability**, such as "a read never returns a
value older than the last acknowledged write". Rejected: such checks miss exactly the violations that
involve concurrency and unknown outcomes, which are the interesting ones.

**Treat timed-out operations as failed.** Much simpler, and wrong. It reports a violation every time
a timed-out write turns out to have been committed.

## Consequences

- The first optimisation added for unknown outcomes was unsound in combination with the original
  stopping condition. The brute-force comparison caught it on its first random history, which is the
  argument for this ADR's last decision.
- Histories of a few thousand operations over a handful of keys check in well under a second.
  Histories with many unknown outcomes on one key remain expensive, and are reported as UNKNOWN
  rather than guessed at.
- The model is pluggable. `KvModel` describes the key-value store; any other state machine can be
  checked by writing a model for it.
