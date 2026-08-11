# ADR-0006: Time enters the core as logical ticks

- Status: Accepted
- Date: 2026-08-05

## Context

Raft is defined in terms of timeouts: a follower becomes a candidate after not hearing from a
leader for an election timeout, a leader sends heartbeats at some interval. The obvious
implementation reads a clock and compares timestamps.

That obvious implementation has two costs that are easy to underestimate. A cluster with a
one-second election timeout takes real seconds to test, so a scenario covering ten leader changes
takes ten seconds and a suite of thousands of randomized scenarios becomes impossible. And
behaviour becomes a function of the machine: the same input produces different interleavings on a
loaded CI runner than on a developer laptop, which is how a test earns the label "flaky" and then
gets disabled.

## Decision

The core has no clock. It exposes a `tick()` method and counts calls to it. Every timeout in
`RaftConfig` is expressed in ticks:

```java
electionTimeoutMinTicks = 10
electionTimeoutMaxTicks = 20
heartbeatTicks          = 1
```

How long a tick lasts is a property of the runtime, and only the runtime knows it. In production
a scheduled executor calls `tick()` every few tens of milliseconds. In the simulation, ticks are
events on a priority queue and a simulated hour costs microseconds.

There is deliberately no `Clock` port. A port would suggest that wall-clock time is something the
core needs but abstracts over; it does not need it at all.

## Alternatives considered

**A `Clock` port returning nanoseconds**, with a fake implementation in tests. This is the common
Java answer, and it does make tests deterministic. Rejected because it keeps timeout arithmetic
in the core in real-time units, which means the simulation still has to model a plausible passage
of time rather than simply stepping. It also leaves the door open to reading the clock somewhere
that was not meant to — an architecture test can forbid a specific method, but not the intent.

**Real time with generous tolerances in tests.** Rejected outright: this is the design that
produces suites where a handful of tests fail on a busy machine and everyone learns to re-run.

## Consequences

- A simulated cluster runs at whatever speed the CPU allows, with no sleeping anywhere. This is
  the single largest reason thousands of randomized scenarios per CI run are feasible.
- Timeout values are unitless in the core and only become milliseconds in the runtime
  configuration. This is a real readability cost: "10 ticks" means nothing without knowing the
  tick duration, so both the configuration documentation and the error messages must spell out
  the relationship.
- Anything that genuinely needs wall-clock time — a TTL on a key, a lease deadline — cannot get
  it from the core. Where the state machine needs a timestamp it must be *replicated*: the leader
  writes its own time into the log entry, and every replica uses that value. A replica that read
  its own clock would diverge from the others, which is the subject of a later ADR.
- The runtime, not the core, owns the mapping from ticks to milliseconds, and therefore owns the
  decision about how tick drift under GC pauses is handled.
