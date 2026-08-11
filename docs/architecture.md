# Architecture

> Status: reflects phase 2 of 15. The domain model, the ports and the `Ready` contract exist; the
> algorithm that produces a `Ready` arrives in phases 3 and 4. Sections describing later phases
> are marked as such.

## The central decision

The Raft algorithm lives in `flotilla-core` as a **pure state machine**. It performs no I/O,
starts no threads, never reads a wall clock and never uses unseeded randomness.

This is not stylistic. Consensus bugs live in unlucky interleavings — a leader that crashes after
replicating but before responding, a partition that heals at the wrong moment, a disk that stalls
for two seconds. Those cannot be enumerated by hand, and in a conventional design they cannot be
reproduced either, because the outcome depends on the machine. A deterministic core removes the
machine from the equation: an entire cluster runs in one thread on virtual time, and any failure
replays exactly from a seed.

Everything else in this document follows from that.

```
              inputs                                        output
   ┌──────────────────────────┐                 ┌───────────────────────────┐
   │ step(RaftMessage)        │                 │ Ready                     │
   │ tick()                   │ ───────────────▶│   hardStateToPersist      │
   │ propose(command)         │   flotilla-core │   entriesToPersist        │
   │ advance()                │                 │   messagesToSend          │
   └──────────────────────────┘                 │   committedEntriesToApply │
                                                │   softStateChange         │
                                                │   readStates              │
                                                └───────────────────────────┘
```

The core *decides*. The runtime *executes*. Nothing in the core opens a file or a socket.

## The `Ready` contract

`Ready` is the boundary between the deterministic core and the impure world. A runtime must
process it in this order:

1. Durably persist `hardStateToPersist` and `entriesToPersist` — **synced**, not merely written.
2. Only then send `messagesToSend`.
3. Hand `committedEntriesToApply` to the state machine.
4. Answer `readStates` once the state machine has applied far enough.
5. Signal completion, so the core can produce the next `Ready`.

Steps 1 and 2 are a safety requirement, not a preference. A follower that acknowledges entries it
has not synced and then crashes has told the leader those entries are safe when they are not; if
that follower was part of the majority the leader counted, a committed entry is gone. The same
applies to a vote acknowledged before `votedFor` is durable, which permits two leaders in one
term.

The core cannot enforce this — it cannot observe what the runtime does. It states the contract on
the type, and the runtime is tested against it (phase 7).

## Ports

The core declares three interfaces and implements none of them.

| Port | Used by the core | Notes |
|---|---|---|
| `LogStore` | reads only | Writes are emitted in a `Ready` and carried out by the runtime. |
| `StableStore` | `load()` at startup only | `persist()` is never called by the core: it is slow, must be batched into one fsync with the log append, and must complete before the messages of that cycle are sent. |
| `RandomSource` | randomized election timeouts | Seeded and injected, so a failure found after ten million simulated events can be replayed. |

There is deliberately **no clock port**. Time enters as logical `tick()` calls — see
[ADR-0006](adr/0006-tick-based-logical-time.md).

Two adapter sets exist and must stay behaviourally identical: the production one backed by files
and sockets, and the simulated one backed by virtual time and an in-memory network. Contract tests
run against both, because an adapter that merely looks equivalent is how a simulation stops
proving anything about production.

## Modules

Three modules, split along dependency classpaths rather than along layers — see
[ADR-0005](adr/0005-three-module-layout.md).

| Module | Packages | Dependencies |
|---|---|---|
| `flotilla-core` | `core`, `core.message`, `core.port` | none |
| `flotilla-node` | `storage`, `kv`, `transport.grpc`, `server`, `client`, `cli` | core, plus gRPC, protobuf, picocli |
| `flotilla-testing` | `sim`, `bench`, `it` | core, node, plus JMH and Testcontainers |

Layering *between* modules is enforced by the compiler: `flotilla-core` cannot reference the
others because they are not on its classpath. Layering *inside* a module is enforced by ArchUnit
rules that fail the build like any other test.

## What is checked mechanically

Because these properties are invisible — violating them produces no symptom until much later —
they are tests rather than conventions:

| Rule | Where |
|---|---|
| The core performs no I/O | `CoreArchitectureTest` |
| The core starts no threads and uses no concurrency primitives | `CoreArchitectureTest` |
| The core never reads a clock | `CoreArchitectureTest` |
| The core uses no unseeded randomness | `CoreArchitectureTest` |
| The core uses no hash-ordered collections | `CoreArchitectureTest` |
| The core depends on no third-party library | `CoreArchitectureTest` |
| Messages have only final fields | `CoreArchitectureTest` |
| Storage, state machine and transport are leaves | `NodeLayeringTest` |
| The simulation depends on the core only | `SimulationIsolationTest` |
| Tests run on the pinned JDK | `ToolchainSmokeTest` |

The hash-ordered-collection rule is the least obvious and the most valuable: `Set.of` and
`Set.copyOf` randomize their iteration order per JVM run by design. Iterating peers in such an
order makes two runs of the same seed diverge, and the resulting bug reproduces on nobody's
machine.

## Thread model (phase 7)

Not yet implemented. The intent, recorded here because the `Ready` design depends on it:

- one **event loop** thread owns the core exclusively and consumes a bounded queue, so the core
  needs no locks;
- a **storage writer** batches appends into a single fsync per batch (group commit);
- an **apply loop** applies committed entries, so a slow state machine cannot stall replication;
- every queue is bounded and has an explicit overflow policy.

## Further reading

- [ADR index](adr/README.md) — every non-obvious decision and what it cost
- [ROADMAP.md](../ROADMAP.md) — the phase plan and why it is ordered that way
