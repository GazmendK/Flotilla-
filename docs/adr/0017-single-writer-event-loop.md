# ADR-0017: One thread owns the Raft state

- Status: Accepted
- Date: 2026-08-18

## Context

Phase 6 ended with a correct consensus core and a durable log, both of which are single-threaded
by construction. A running node is not: ticks arrive from a timer, messages arrive from the
network, proposals arrive from client threads, and all three mutate the same Raft state.

The core deliberately has no locks — it is a plain object with mutable fields and a
`ready()`/`advance()` protocol that assumes a single caller. Something has to supply that
assumption, and the choice of what determines almost everything about how the node behaves under
load.

## Decision

**Exactly one thread ever touches `RaftNode`.** Every input becomes a `NodeEvent` on a bounded
queue; the event loop takes one event at a time, steps the core, drains the resulting `Ready`,
and calls `advance()`. There is no lock anywhere in the node, because there is nothing to
contend for.

Three consequences follow directly:

- **State is published, not shared.** Callers of `isLeader()`, `currentTerm()` or `commitIndex()`
  read `volatile` fields that the event loop writes after each `Ready`. No other thread may
  dereference `RaftNode` at all, which is a rule a reviewer can check by looking at the field's
  visibility rather than by reasoning about interleavings.
- **The queue is bounded and overflow has a stated policy per event type.** A proposal that does
  not fit is rejected with a `BackpressureException` naming the capacity it hit — the caller
  learns immediately instead of waiting on a future that a growing queue will honour minutes from
  now. A tick that does not fit is dropped and counted, because a full queue means the loop is
  busy and the next tick is already on its way. Inbound messages are dropped, which the Raft
  protocol already tolerates: the sender retries.
- **A proposal is acknowledged when it is applied, not when it is appended.** The registry keys
  waiting futures by index *and* term, so an index that a later leader overwrote fails the caller
  rather than reporting a commit that never happened.

Shutdown is part of the contract, not an afterthought: every future that was registered is failed,
every proposal still sitting in the queue is failed, and the loop's thread is joined with a
timeout before the log and the stable store are closed. A client is never left holding a future
that nothing will complete.

## Alternatives considered

**A lock around `RaftNode`.** The obvious choice, and it works. Rejected because the interesting
part of a node's behaviour is what happens when it falls behind, and a lock has no answer for
that: callers queue up invisibly in the monitor, latency grows without bound, and there is no
place to put an overflow policy or a metric. A queue makes the backlog a first-class object with
a depth, a limit, and a counter for what it refused.

**Virtual threads with a synchronized core.** Cheap threads make the lock cheaper, not the
unbounded backlog. The problem was never the cost of blocking.

**An actor library or a `Disruptor`.** Both fit the shape of the problem. Rejected for a
dependency-cost reason: the entire mechanism here is one `ArrayBlockingQueue` and one thread, and
a reader who understands those two things understands the node.

## Consequences

- Persistence and state machine application still run on the event loop in this change, so a slow
  `fsync` stalls tick processing. That is a real limitation with a real symptom — under a blocked
  state machine the node stops accepting proposals — and it is what the next change addresses by
  moving both off the loop.
- The state machine must be deterministic and reasonably fast. A `StateMachine` that blocks is
  indistinguishable from a hung node, which the backpressure test relies on to produce overload
  on purpose.
- Restart replays the log into the state machine before the loop starts, so a fresh process
  reaches the same state as the one it replaced. Deleting that single call fails the recovery
  tests, which is the check that it is load-bearing.
- Determinism is unaffected: the simulation drives `RaftNode` directly and never constructs a
  `RaftServer`. The event loop is a delivery mechanism for the same steps, not a second
  implementation of them.
