<h1 align="center">Flotilla</h1>

<p align="center">
  <strong>A distributed key-value store with a complete Raft implementation —<br>
  whose correctness is <em>demonstrated</em>, not claimed.</strong>
</p>

<p align="center">
  <a href="https://github.com/GazmendK/Flotilla-/actions/workflows/ci.yml"><img alt="CI" src="https://github.com/GazmendK/Flotilla-/actions/workflows/ci.yml/badge.svg"></a>
  <a href="LICENSE"><img alt="License" src="https://img.shields.io/badge/license-Apache--2.0-blue.svg"></a>
  <img alt="Java" src="https://img.shields.io/badge/Java-25-orange.svg">
</p>

---

> **Status: Phase 6 of 15 — durability.**
> The log now lives on disk in a documented, checksummed format, and recovery is verified by
> crashing at every single physical write. The runtime that ties consensus and storage together
> is Phase 7. See [`ROADMAP.md`](ROADMAP.md) for the full plan.

## Why this exists

Implementing Raft is a well-trodden exercise. Knowing whether your implementation is *correct* is
not — and that is the part this project is actually about.

Flotilla's consensus core is a pure state machine: it performs no I/O, starts no threads, never
reads a wall clock and never uses unseeded randomness. Time enters as logical ticks; effects leave
as data. That constraint costs an extra layer of indirection and buys something unusual: an entire
cluster, with network partitions, message loss and crashing nodes, runs deterministically in a
single thread. Thousands of randomized failure scenarios execute per CI run, every one of them
reproducible from a seed, with the five safety properties from the Raft paper checked after every
single step.

The same approach is used by FoundationDB and TigerBeetle. It is the difference between "I tested
it a few times" and "here is the seed that reproduces the bug".

## Quickstart

Not yet available — it arrives with the container images in Phase 14. Until then:

```bash
git clone https://github.com/GazmendK/Flotilla-.git
cd Flotilla-
./gradlew build
```

No JDK installation required: Gradle provisions the pinned Java 25 toolchain itself.

## Correctness

This section is the point of the project. It will fill in as the phases land:

| | Mechanism | Phase |
|---|---|---|
| ☑ | Deterministic simulation: virtual clock and network, partitions, crashes, seeded and reproducible | 5 |
| ☑ | All five safety properties from Figure 3 of the Raft paper, checked after every step | 5 |
| ☑ | Figure 7 and Figure 8 of the paper encoded as test cases | 4 |
| ☑ | Fault injection proving the checkers actually detect known-bad behaviour | 5 |
| ☑ | Crash-consistency verified by injecting a crash at every physical write | 6 |
| ☐ | A linearizability checker, run over real cluster histories | 11 |
| ☑ | Every safeguard tested with itself disabled, so it is known to fail without it | 3 |
| ☑ | Determinism enforced as tests: the core cannot acquire I/O, threads, a clock or unseeded randomness | 2 |
| ☑ | Static analysis as build failures: Error Prone, NullAway, `-Werror` | 1 |
| ☑ | CI on Linux, macOS **and Windows** — file and fsync semantics differ, and storage bugs hide there | 1 |

## Architecture

Three modules. The split follows dependency classpaths — the one boundary packages cannot
express. Layering *inside* a module is enforced by architecture tests instead.

```
┌─ flotilla-node ─────────────────────────────────────────┐
│                                                         │
│   cli · client        command line and client library   │
│   server              event loop, group commit, apply   │
│   transport.grpc      peer and client RPC               │
│   storage             WAL, stable store, snapshots      │
│   kv                  replicated state machine          │
│                                                         │
│   depends on: gRPC, protobuf, picocli                   │
└───────────────────────┬─────────────────────────────────┘
                        │
┌───────────────────────▼─────────────────────────────────┐
│ flotilla-core     the Raft algorithm as a pure state    │
│                   machine — no I/O, no threads, no      │
│                   clock. Zero dependencies, enforced    │
│                   by the compiler, not by discipline.   │
└───────────────────────▲─────────────────────────────────┘
                        │ same ports, different adapters
┌───────────────────────┴─────────────────────────────────┐
│ flotilla-testing                                        │
│   sim                 virtual clock and network, fault  │
│                       injection, invariant and          │
│                       linearizability checking          │
│   bench · it          JMH, load generator, Testcontainers│
└─────────────────────────────────────────────────────────┘
```

The `sim` package and the production runtime implement the *same ports*. What the simulation
exercises is the shipped consensus code, not a model of it.

## Roadmap

| Phase | | Phase | |
|---|---|---|---|
| 1. Foundation | ☑ | 9. gRPC transport, real cluster | ☐ |
| 2. Domain model and ports | ☑ | 10. Snapshots and compaction | ☐ |
| 3. Leader election | ☑ | 11. Linearizable reads and checker | ☐ |
| 4. Log replication | ☑ | 12. Membership changes | ☐ |
| 5. Deterministic simulation | ☑ | 13. Observability and visualizer | ☐ |
| 6. Persistence and recovery | ☑ | 14. CLI, packaging, demo | ☐ |
| 7. Node runtime | ☐ | 15. Benchmarks and release | ☐ |
| 8. KV state machine, sessions | ☐ | | |

## Non-goals

Stated up front, because a bounded scope is a design decision:

- **No Byzantine fault tolerance.** The failure model is crash-recovery with fair-loss links.
  Nodes may crash, messages may vanish — but nobody lies.
- **No multi-key transactions, no SQL.** Compare-and-swap yes; distributed transactions are a
  roadmap item, not a v1.0 feature.
- **No sharding / multi-Raft in v1.0.** The core is deliberately built to allow it later.
- **Not a replacement for etcd.** This is a reference-quality implementation built to be
  understood and verified, not a product with an operations team behind it.

## Documentation

| Document | Contents |
|---|---|
| [`docs/architecture.md`](docs/architecture.md) | The pure core, the `Ready` contract, the ports, and what is checked mechanically |
| [`docs/raft-implementation.md`](docs/raft-implementation.md) | Every rule of Figure 2 mapped to a code location or to the phase that implements it |
| [`docs/simulation.md`](docs/simulation.md) | How the simulation works, how to replay a seed, and what it provably does *not* catch |
| [`docs/storage-format.md`](docs/storage-format.md) | The on-disk format, byte for byte, with a hexdump generated from the code |
| [`docs/testing-strategy.md`](docs/testing-strategy.md) | The layers, and the rule that every safeguard is tested with itself disabled |
| [`docs/adr/`](docs/adr/) | Architecture decision records — every non-obvious choice, and what it cost |
| [`ROADMAP.md`](ROADMAP.md) | The 15 development phases, and why they are ordered that way |
| [`CONTRIBUTING.md`](CONTRIBUTING.md) | How to build, and what "done" means here |
| [`SECURITY.md`](SECURITY.md) | Threat model and what counts as a vulnerability here |

## Acknowledgements

Built on the work of Diego Ongaro and John Ousterhout ([the Raft paper](https://raft.github.io/raft.pdf)
and [the dissertation](https://github.com/ongardie/dissertation)). The `Ready`/`advance` core
design follows the approach taken by [etcd/raft](https://github.com/etcd-io/raft) and
[tikv/raft-rs](https://github.com/tikv/raft-rs); the deterministic simulation approach follows
FoundationDB and TigerBeetle. The linearizability checker builds on Herlihy and Wing, and on the
practical refinements published in Porcupine and Jepsen's Knossos.

## License

[Apache License 2.0](LICENSE).
