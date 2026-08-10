# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

Until 1.0.0, each development phase is released as a `0.x.0` minor version. The public API is
not stable before 1.0.0.

## [Unreleased]

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

[Unreleased]: https://github.com/OWNER/flotilla/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/OWNER/flotilla/releases/tag/v0.1.0
