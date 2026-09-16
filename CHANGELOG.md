# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

Until 1.0.0, each development phase is released as a `0.x.0` minor version. The public API is
not stable before 1.0.0.

## [Unreleased]

### Added

- Linearizability checked under faults. Simulated clients write through the leader and read through
  ReadIndex, lease reads, or straight from a replica, while nodes crash, partitions come and go and
  snapshots are installed; every history goes through the checker. ReadIndex and lease reads pass on
  every seed. Reading straight from a replica is caught.
- A targeted scenario for the textbook bug: a leader cut off in a minority, still believing it leads,
  receives a read after the majority has elected a new leader and accepted a write. Answering without
  confirming leadership is caught there — and only there, because CheckQuorum closes that window
  within two election timeouts and random partitions almost never land in it.
- ReadIndex in the consensus core: a leader answers a read without writing to the log once a
  majority has acknowledged a heartbeat round sent *after* the read arrived, and only after it has
  committed an entry of its own term. Reads that arrive while a round is in flight share the next one,
  and a follower forwards a read to the leader and serves it locally. Acknowledgements are matched to
  rounds carried on every `AppendEntries` message; counting any reply instead releases a read on the
  strength of a heartbeat sent before it, which `ReadIndexTest` shows.
- Lease reads, off by default. The lease is shortened by a configured clock-drift bound, requires
  CheckQuorum, and a node that has just started refuses to vote for one election timeout — otherwise
  a crashed follower forgets the lease it granted and votes for a new leader while the old one still
  answers reads.
- A linearizability checker, written for this project and depending on nothing but the JDK. It
  follows Porcupine's search, checks each key independently, treats a timed-out operation as one
  that may or may not have happened, and reports UNKNOWN when it runs out of time instead of passing.
  A failing history is rendered as a timeline that marks the operation no order can explain.
- The checker is itself checked: it rejects the textbook violations, accepts 3,000-operation
  histories produced by a real sequential execution, catches a single corrupted read in them every
  time, and agrees with a brute-force search on 50,000 small random histories. That comparison
  caught an unsound optimisation on the first history it saw.
- A follower that has fallen behind a compacted prefix is caught up over the network. The leader
  splits the snapshot into chunks and sends them in order on a thread of their own, so the event
  loop never waits for a transfer; the receiver reassembles them by offset, so a reordered or
  duplicated chunk costs nothing and a lost one simply leaves the transfer incomplete until the
  leader starts it again. A transfer larger than the configured limit is refused rather than
  buffered, and a newer snapshot abandons a half received older one instead of mixing the two.
- `InstallSnapshotIT` stops a node, writes past the point where its entries are deleted, and starts
  it again with a message limit smaller than the snapshot — so the test only passes if the transfer
  is genuinely chunked. Sending the snapshot as one message leaves the node stuck forever.
- `docs/operations.md`: how to back a cluster up, how to restore it, how large snapshots get, which
  numbers to watch, and what each of them means when it goes wrong.
- Snapshots are taken at runtime and the log no longer grows without bound. The apply loop stops only
  long enough to copy the state, a background thread serializes and writes it, and the event loop
  discards the compacted prefix. Measured on 200,000 keys: 15 ms to copy, 29 ms to encode, so two
  thirds of the pause is gone — and a test fails if copying ever stops being the cheap half.
- A node restarts from its newest snapshot plus the entries after it instead of replaying its whole
  history, and `RaftServer` reports the snapshot count, the compaction count, the restore count and
  the longest apply pause.
- Snapshot files on disk: temporary file, fsync, atomic rename, directory fsync, with the format and
  the write sequence documented byte for byte. A crash at any of those operations leaves either the
  previous snapshot or the new one and never half of either, and the whole crash schedule now runs
  against two filesystem models — one where a directory entry becomes durable only at a directory
  fsync, and one that journals metadata eagerly. Writing straight to the final name passes the first
  and fails the second, which is the case the rename exists for.
- The newest two snapshots are kept. A snapshot that fails its checksum is skipped in favour of the
  one before it, and a temporary file left behind by a crash is deleted on start.
- The simulation now snapshots and compacts while it runs, so a node that was down while the cluster
  moved past it is caught up by a real `InstallSnapshot` under crashes and partitions. Its state
  machine folds every applied entry into a digest and a snapshot carries that digest, which turns
  "the follower caught up" into a checkable claim: two replicas that have applied through the same
  index must hold the same state.
- The invariant checkers address log entries by index instead of by position. Three of them compared
  the first entry of one log against the first entry of another, which is correct only while every
  log starts at index 1; against a compacted log one reported a violation that was not there and
  another stopped checking the entries that mattered.
- The snapshot protocol in the consensus core. A leader whose log no longer reaches a follower sends
  it a snapshot instead of entries, tracks that peer in a `SNAPSHOT` state so it receives nothing
  else meanwhile, and starts the transfer over if the answer never arrives. A follower refuses a
  snapshot older than what it has committed, keeps its log when the snapshot only covers a prefix it
  already holds, and otherwise replaces the log wholesale. Every one of those rules is checked by
  turning it off: each makes exactly one test fail.
- `RaftNode.compactLog` refuses to discard entries no snapshot covers, and refuses to run ahead of
  the state machine. Compacting entries nothing else holds is the one failure that cannot be
  repaired afterwards.
- Snapshots carry the cluster configuration they were taken under, so Phase 12 does not have to
  change the wire format to get it.
- Log compaction in the storage layer. `compactTo` discards a prefix a snapshot covers while keeping
  the term of its last entry, which the next append after a snapshot depends on; `resetTo` discards
  the whole log when a follower installs a snapshot its log does not match. Both are part of the
  shared log contract, so the in-memory and the on-disk store behave identically.
- A small `logbase` file, two checksummed slots like the hard state, is the commit point of every
  compaction. Resetting truncates and syncs the old log before writing the new base, and a crash test
  at every durability operation proves that the discarded log never comes back — writing the base
  first resurrects an old entry at the third crash point.

- A client library. `FlotillaClient` finds the leader from any node, follows redirects without
  waiting, backs off with full jitter, and retries with the same session sequence so a command whose
  acknowledgement was lost is answered from the cache instead of running again. Against three real
  nodes with the leader killed mid-sequence, thirty compare-and-swap increments leave the counter at
  exactly thirty.
- Outcomes are reported in Jepsen's vocabulary. Every command is recorded as `INVOKE` followed by
  `OK`, `FAIL` or `INFO`, and a client that cannot know whether a command took effect says so with
  `IndeterminateResultException` rather than reporting a failure that may be false.
- A second RPC, `ClientService.Execute`, carrying the state machine's own encoded request, with a
  documented error model: redirects as `UNAVAILABLE` with the leader in trailers, overload as
  `RESOURCE_EXHAUSTED`, undecodable commands as `INVALID_ARGUMENT`.
- A message from a peer whose channel is backing off now cuts that backoff short. After a ten-second
  outage gRPC's own backoff would wait several more seconds before reaching a node that restarted on
  its old port; with the hint, a test bounds it below three.
- `docs/wire-protocol.md`, with sequence diagrams for election, replication and a request that
  survives a leader change.

- Nodes talk to each other over gRPC. A versioned protobuf schema carries every Raft message in a
  single one-way `Deliver` envelope, because the core already speaks asynchronous messages and a
  request/response RPC per type would re-couple what it deliberately decoupled. `buf lint` runs in
  CI, and `buf breaking` guards the wire format on every pull request.
- A slow or dead peer can never stall the event loop or grow memory: deliveries per peer are bounded,
  and a message that does not fit is dropped, which Raft already tolerates. Every message is
  accounted for as delivered, dropped or failed.
- Startup refuses configurations that cannot work: a delivery deadline that outlasts an election
  timeout, or a message limit smaller than the largest batch a leader may send.
- A three-node cluster over real sockets: leader election, replication to every state machine,
  failover without losing an acknowledged write, and a follower restarted on a new port catching up.
- Generated protobuf types never leave the transport, enforced by an architecture test, and the
  state machine and the log may not depend on gRPC or protobuf at all.

- Exactly-once execution through client sessions (thesis §6.3). A client registers once and numbers
  its requests; the state machine caches the last response per client, so a retry after a lost
  acknowledgement returns the stored answer instead of running again. Without it a Raft store is
  only at-least-once — a retried compare-and-swap re-executes, sees its own earlier write, and
  reports failure to the caller that actually succeeded.
- A client id is the log index of its registration, so every replica derives the same one with no
  coordination and a restored snapshot yields the same ids again.
- Sessions expire after a number of **log entries**, never after wall-clock time. A replica applies
  an entry whenever it gets there, so expiry measured against a local clock would make replicas
  disagree about whether a session exists — that is the replicated state itself diverging.
- The session table, the cached responses and the applied index are part of the snapshot. Removing
  the sessions from the encoder makes two tests fail; removing the response cache makes six fail,
  including both end-to-end cases through `RaftServer`.
- `RaftServer.execute` returns the state machine's response, alongside `propose`, which still
  reports the index.
- `docs/consistency-model.md` states what is guaranteed and what is not — no multi-key atomicity, no
  snapshot isolation, no cross-session causality — and where exactly-once ends: an expired session
  is answered with an explicit refusal, never a silent re-execution.

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

- Group commit had no physical effect with the default storage policy. `FsyncPolicy.ALWAYS` forced
  the log after every append, and the consensus core appends each proposal as it steps it — so every
  proposal paid its own `fsync` before the event loop could batch anything, and the published "33
  fsyncs for 2001 entries" counted the loop's calls rather than the disk's forces. A new default,
  `FsyncPolicy.BATCHED`, forces the log only on `sync()` and the hard state on every write; the store
  counts physical forces, and the group commit test now asserts on those. Found through a CI timeout
  on a runner with a slow disk. `NEVER` now means never, including `sync()`.
- The property-based tests never ran. Every one of them carried JUnit's `@DisplayName`, and jqwik
  silently skips a property annotated that way — so the record decoder fuzzing, the model-based
  key-value test and the codec round trips had been reported as skipped since they were written.
  They are now plain seeded tests that name the seed and attempt in every failure, the two mutation
  and truncation checks are exhaustive rather than sampled, and jqwik is no longer a dependency. Run
  for the first time, all of them hold; replacing compare-and-swap with an unconditional write makes
  the model-based test fail on its first attempt.
- A crash during a truncation that spanned several segments could leave the log with a gap, and a
  node that hit it refused to start for good. Truncation now deletes and syncs away the later
  segments before shortening the one it keeps. Found once the crash test stopped treating directory
  changes as instantly durable and started crashing at syncs and deletes as well as writes.
- A client command the state machine could not decode was proposed anyway and threw on the apply
  thread, which stops the node for good. Commands are now validated before they are proposed.
- A message addressed to a different node made `RaftNode.step` throw on the event loop, which stops
  the node for good. Over the network that is an input rather than a bug, so the transport now
  refuses it at the edge; a test shows both the refusal and the crash it prevents.
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
