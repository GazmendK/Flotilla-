# ADR-0004: Keep the Raft core pure and deterministic

- Status: Accepted
- Date: 2026-08-05

## Context

The failures that matter in a consensus implementation are the ones that need an unlucky
interleaving to appear: a leader that crashes after replicating but before responding, a network
partition that heals at exactly the wrong moment, a follower whose disk stalls for two seconds.
These are the failures that lose committed data.

Such failures cannot be found by writing test cases by hand, because the interesting ones are the
ones nobody thought of. They also cannot be debugged from a production log, because by the time
the symptom appears the cause is hours in the past and irreproducible.

The usual structure of a Raft implementation makes this worse: the algorithm is entangled with
sockets, files, threads and timers, so a test needs all four to be involved.

## Decision

`flotilla-core` contains the Raft algorithm as a **pure state machine**:

- **No I/O.** The core does not open files or sockets.
- **No threads.** The core never starts one and is never entered concurrently.
- **No wall clock.** Time enters as logical `tick()` calls. Election and heartbeat timeouts are
  counted in ticks, not milliseconds.
- **No free randomness.** Randomized election timeouts draw from an injected, seedable source.
- **No hidden iteration order.** Ordered collections only; a `HashMap` iteration would make two
  runs of the same input diverge.

Inputs are `step(message)`, `tick()` and `propose(command)`. The output is a `Ready` value that
*describes* what must happen — entries to persist, messages to send, committed entries to apply.
The runtime layer performs those effects and reports back via `advance()`.

The rule is enforced mechanically by an ArchUnit test, not by convention.

## Alternatives considered

**The conventional design**: a `RaftNode` that owns a socket, a log file and a timer thread.
Simpler to write and to read initially. Rejected because it makes the interesting failure modes
untestable, which is precisely the property this project exists to demonstrate.

**A separate model for verification**, with the production code written normally. This is what a
TLA+ specification does, and it is valuable — but a model can drift from the implementation. Here
the simulation drives the *shipped* code, so there is nothing to drift. A TLA+ model of the
membership protocol remains on the roadmap as a complement, not a replacement.

**Purity by convention plus code review.** Rejected. A single `System.nanoTime()` added in a hurry
silently destroys reproducibility, and nothing fails. If the property is load-bearing, a test must
guard it.

## Consequences

**What this buys:**

- An entire cluster — with partitions, message loss, duplication, reordering and crashes — runs
  in one thread on virtual time. Thousands of randomized scenarios per CI run.
- Every run is driven by a seed, so any failure is reproducible with one command. There is no
  such thing as a flaky failure here; there are only failures we have not diagnosed yet.
- The core needs no locks, because it is never entered concurrently. The concurrency argument for
  the whole system reduces to the runtime layer.
- The core has no dependencies at all, so it can be published as an embeddable library, and many
  instances can live in one process — which is what a future multi-Raft layer would require.

**What this costs:**

- An extra layer. The `Ready`/`advance` protocol is more indirect than calling `socket.send()`,
  and it takes a reader longer to follow the first time.
- The ordering constraint moves into the runtime: persisting state before sending the
  corresponding messages is a safety requirement, and the core can only express it, not enforce
  it. This is documented on `Ready` itself and covered by tests.
- Two implementations of every port (real and simulated) must be kept behaviourally equivalent.
  Contract tests that run against both are the mitigation.
