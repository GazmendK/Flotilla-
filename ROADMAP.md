# Roadmap

Flotilla is built in ordered phases, each one a self-contained, reviewable change that leaves the
build green. The ordering follows one principle: **correctness infrastructure comes before
convenience.**

The consensus algorithm is implemented before persistence and networking exist, because a
consensus bug is far easier to find when sockets, files and threads are not also in the picture.
The deterministic simulation lands in phase 5 rather than at the end, so that every subsequent
phase inherits fault injection and invariant checking for free.

## Status

| Phase | Scope | Status |
|---|---|---|
| 1 | Build, toolchain, quality gates, CI across three platforms | **done** |
| 2 | Domain model, message types, ports, enforced architecture rules | **done** |
| 3 | Leader election, PreVote, CheckQuorum | **done** |
| 4 | Log replication, commit rules, conflict backtracking | **done** |
| 5 | Deterministic simulation and the five Raft safety invariants | **done** |
| 6 | Segmented write-ahead log, stable store, crash recovery | **done** |
| 7 | Node runtime: single-writer event loop, group commit, backpressure | **done** |
| 8 | Key-value state machine, client sessions, exactly-once semantics | planned |
| 9 | gRPC transport, client library, real multi-process cluster | planned |
| 10 | Snapshotting, log compaction, streamed InstallSnapshot | planned |
| 11 | Linearizable reads (ReadIndex, lease, follower) and a linearizability checker | planned |
| 12 | Membership changes with learners, and leader transfer | planned |
| 13 | Metrics, structured logging, live cluster visualization | planned |
| 14 | CLI, layered configuration, container images, chaos demo | planned |
| 15 | Benchmarks, performance analysis, `v1.0.0` | planned |

## After 1.0

Ordered by expected value, not by effort:

- **A TLA+ specification of the membership-change protocol.** Model checking explores states that
  randomized simulation reaches only by luck, and membership changes are where the subtle
  failures live.
- **A Jepsen test suite** with partition, clock-skew and process-pause nemeses. The pause nemesis
  in particular exercises the assumption that lease reads depend on.
- **A second transport implementation** over raw Netty framing, alongside a fair benchmark
  against gRPC — the pluggable transport port is worth little if it is never exercised twice.
- **Watch API with MVCC revisions**, which is what turns a key-value store into something usable
  for service discovery and configuration.
- **Multi-Raft with range sharding**, including heartbeat coalescing — the step from "a Raft
  cluster" to how production systems actually scale.
- **Backup and restore to object storage**, with point-in-time recovery.

## Explicit non-goals

- Byzantine fault tolerance. The failure model is crash-recovery with fair-loss links.
- Multi-key transactions and SQL.
- Being a drop-in replacement for etcd or Consul.
