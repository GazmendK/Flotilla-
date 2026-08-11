# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

Until 1.0.0, each development phase is released as a `0.x.0` minor version. The public API is
not stable before 1.0.0.

## [Unreleased]

### Added

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
