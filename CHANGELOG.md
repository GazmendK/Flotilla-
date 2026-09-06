# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

Until 1.0.0, each development phase is released as a `0.x.0` minor version. The public API is
not stable before 1.0.0.

## [Unreleased]

### Added

- A deterministic key-value state machine: an ordered map with put, delete, get, compare-and-swap
  and half-open range scans. Compare-and-swap covers create-if-absent and delete-if-equal, which is
  what a lock on top of the store is built from.
- Commands, responses and snapshots have a hand-written canonical encoding, so `flotilla-node`
  keeps its zero runtime dependencies and two replicas that reached the same state produce
  byte-identical snapshots. Decoding is total: arbitrary bytes yield a `MalformedCommandException`
  and never a buffer error or an allocation from a hostile length.
- `DeterminismTest` holds the property that matters in practice — a replica that restored a
  snapshot and caught up must match one that replayed the whole log. Substituting a `HashMap` for
  the ordered map makes three of its four cases fail.
- A model-based property test running random command sequences against a reference map, and a
  `StateMachine` port widened with `snapshot`, `restore` and `lastAppliedIndex` so a different
  machine — a counter, a queue, a lock service — can be dropped in.

- Group commit: the event loop drains a batch of events before producing one `Ready`, so a burst of
  proposals shares a single `fsync`. Measured at 33 syncs for 2001 entries, and a test with the
  batch size pinned to one shows the same workload otherwise paying one `fsync` per entry.
- Applying committed entries moved to its own thread, so a state machine that blocks no longer stops
  consensus. The commit index keeps advancing while the applied index stalls, and backpressure
  reaches the client instead of accumulating in the node.
- `docs/threading-model.md`: which work runs on which thread, why the log deliberately stays on the
  event loop, and the overflow policy of every queue.

- A running node: one thread owns the Raft state, every input arrives as an event on a bounded
  queue, and no lock exists anywhere in the node. Callers read published `volatile` state and
  never touch the core.
- Overload is answered rather than absorbed. A proposal that does not fit is rejected with a
  `BackpressureException` naming the capacity it hit; a tick that does not fit is dropped and
  counted, because the next one is already on its way.
- A proposal is completed when its entry is applied, and only if the index still carries the term
  it was proposed in — an index a later leader overwrote fails the caller instead of reporting a
  commit that never happened.
- Restart replays the log into the state machine before the loop starts, so a fresh process reaches
  the state of the one it replaced.
- Shutdown fails every future it can no longer honour, registered or still queued, and joins the
  event loop before closing the log. No caller is left waiting on a future nothing will complete.

- Crash consistency proven by exhaustion rather than argued: a fault-injecting file layer crashes
  the workload at every single physical write, and recovery must always yield a prefix of what was
  written with no acknowledged entry missing. The same is done for torn writes, for a full disk,
  and for the hard state.
- Property-based fuzzing of the record decoder: arbitrary bytes may only ever produce a
  `CorruptionException`, a corrupted length is always bounded before anything is allocated, and any
  single-byte mutation of a valid record is detected.

### Fixed

- Stopping the event loop by interrupting its thread destroyed the write-ahead log. `FileChannel`
  is an `InterruptibleChannel`, so an interrupt during a write closes the channel for good and the
  node died with `Cannot write to ....wal`. Shutdown now posts a `Shutdown` event and interrupts
  only as a last resort.
- A proposal in a batch already drained from the event queue was lost if anything threw while the
  batch was being handled: it was neither in the queue nor in the registry, so nothing could ever
  complete its future. The in-flight batch is now failed on the way out.

- A crash during segment creation left a file without a complete header, which recovery treated as
  corruption and refused to start on. A segment without a header provably holds no entries, so it
  is now discarded and the store continues. Found by the crash test on its first run.

- A durable write-ahead log: segment files named after their first index, records framed with a
  length and a CRC32C, and recovery by forward scan that truncates an unreadable tail instead of
  refusing to start. The format is specified byte-exactly in `docs/storage-format.md`, with a
  hexdump generated from the implementation.
- Hard state stored in two alternating 128-byte slots selected by a generation counter, so a write
  interrupted at any point leaves the previous state intact. No temp file, no rename, no directory
  sync on the path taken by every election.
- A `FileIo` port over the file system, so the fault-injecting layer can sit underneath it.
- A shared `LogStore` contract test that runs unchanged against the in-memory and the durable
  implementation, which is what makes the simulation's guarantees transfer to production.

- Deterministic simulation testing: whole clusters run in one thread on virtual time over a
  network that drops, delays, duplicates, reorders and partitions, with crashes that discard every
  unsynced byte. Every run is a pure function of its seed.
- All five safety properties from Figure 3 of the paper, plus applied-exactly-once-and-in-order
  and monotonic progress, checked after every simulation step. The checkers are themselves tested
  against hand-built worlds that violate each property.
- An adversarial fault profile that replicates one entry per message. It exists because a measured
  experiment showed the default profile is blind to commit-rule defects: an injected Figure 8 bug
  survived 500 seeds under the default profile and was caught at seed 77 under this one. See
  `docs/simulation.md`.
- Nightly workflow running eight shards of fresh seeds, with failing runs uploaded as artifacts,
  and `scripts/replay-seed` to reproduce any seed locally.

- Log replication: per-peer progress tracking with probe and replicate phases, batching bounded by
  both entry count and payload size, and a bounded inflight window.
- The commit rule of §5.4.2: a leader advances the commit index only onto entries of its own term,
  and everything before them commits transitively. A leader appends a no-op of its own term on
  election, which is what makes any commit possible after a leadership change.
- Conflict hints on rejected appends, so a follower that diverged by a thousand entries
  resynchronizes in a handful of round trips instead of a thousand.
- Figure 7 and Figure 8 of the paper encoded as tests. Removing the current-term check from the
  commit rule makes `Figure8Test` fail, which is the point of having it.

- Leader election: randomized election timeouts, the term rules from Figure 2, and the election
  restriction of §5.4.1 that compares last term before last index and thereby guarantees Leader
  Completeness.
- PreVote and CheckQuorum, both enabled by default. A partitioned node cycles as `PRE_CANDIDATE`
  at a constant term instead of inflating it, and a leader that stops reaching a majority steps
  down. Both are covered by tests that run the same scenario with the mechanism disabled and
  assert the failure it prevents.
- A leader lease that makes a server ignore vote requests while it still has a leader, applied
  before the term rules rather than after them.
- Roles as a sealed type hierarchy, so adding a role is a compile error at every site that
  switches on it.
- `docs/raft-implementation.md`, mapping every rule of Figure 2 either to a code location or to
  the phase that will implement it.

- The consensus domain model in `flotilla-core`: `NodeId`, `Bytes`, `LogEntry`, `HardState`,
  `SoftState`, `ClusterConfig` with voters and non-voting learners, and `RaftConfig` with
  validation that explains what to change rather than only what is wrong.
- The full RPC hierarchy as a sealed interface, so handling a message is a `switch` the compiler
  checks for completeness: RequestVote (including PreVote), AppendEntries, InstallSnapshot,
  TimeoutNow and ReadIndex.
- The ports the core reaches the world through — `LogStore`, `StableStore`, `RandomSource` — and
  `InMemoryLogStore` as the first implementation. There is deliberately no clock port: time enters
  as logical ticks.
- `Ready`, the contract between the pure core and the runtime, documenting the ordering
  requirement that persistence must complete before the corresponding messages are sent.
- `@RaftSpec`, linking types and methods to the paper or dissertation section they implement.
- Architecture tests that enforce the core's determinism: no I/O, no threads, no wall clock, no
  unseeded randomness, no hash-ordered collections, no third-party dependencies. Package layering
  inside `flotilla-node` and the isolation of the simulation are enforced the same way.
- jqwik for property-based tests, alongside JUnit and AssertJ.

## [0.1.0] - 2026-08-05

Project foundation. No consensus code yet — this release establishes the build and the quality
gates that every later commit has to pass.

### Added

- Three-module Gradle build (`core`, `node`, `testing`) with convention plugins in an included
  `build-logic` build and a version catalog as the single source of versions. The split follows
  dependency classpaths; layering inside a module is enforced by architecture tests.
- Java 25 toolchain, pinned for compilation, tests and the Gradle daemon alike, with automatic
  JDK provisioning so a fresh clone builds without a preinstalled JDK.
- Static analysis as build failures: Error Prone, NullAway with JSpecify `@NullMarked` packages,
  `-Xlint:all` with `-Werror`, and doclint on Javadoc.
- Spotless with palantir-java-format, license headers and LF line endings enforced on all
  platforms.
- JUnit 6 and AssertJ test baseline; `ToolchainSmokeTest` verifies tests run on the pinned JDK.
- Reproducible archives and a Gradle wrapper pinned by SHA-256 checksum.
- CI across Linux, macOS and Windows; CodeQL scanning; Dependabot for Gradle and Actions.
- Apache-2.0 licensing, community health files, and the ADR process with the first four records.

[Unreleased]: https://github.com/GazmendK/Flotilla-/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/GazmendK/Flotilla-/releases/tag/v0.1.0
