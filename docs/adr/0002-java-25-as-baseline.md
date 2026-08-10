# ADR-0002: Use Java 25 as the baseline runtime

- Status: Accepted
- Date: 2026-08-05

## Context

Flotilla needs a language baseline for the whole project. The consensus core in particular
benefits from three things: cheap immutable value types (messages cross thread boundaries and
must be safely publishable), closed type hierarchies that the compiler can check exhaustively
(a missed message type must be a compile error, not a silent no-op), and predictable low-level
file access for the write-ahead log.

## Decision

Java 25 (LTS) is the baseline. The toolchain, the Gradle daemon JVM and CI all pin the same
version:

- `gradle/libs.versions.toml` holds the version,
- `flotilla.java-conventions` configures the compile and test toolchain from it,
- `gradle/gradle-daemon-jvm.properties` pins the daemon JVM to 25 as well,
- the Foojay resolver downloads the JDK if the machine does not have it.

The daemon pin matters beyond compilation: Spotless and Error Prone run inside the Gradle daemon
and use `javac` internals from the JVM they run on. A daemon on an older JDK cannot parse the
language level we compile against, and fails with an error that points nowhere near the cause.

## Alternatives considered

**Java 21 (LTS).** Would also provide records, sealed interfaces, pattern matching for `switch`
and virtual threads, and has the broadest tool support. Rejected because it lacks the finalized
Foreign Function & Memory API, which Phase 6 wants for memory-mapped log segments: `Arena` allows
deterministic unmapping, whereas `MappedByteBuffer` does not — and on Windows an un-unmapped
mapping prevents the file from being deleted, which a log that compacts segments cannot live
with.

**Java 17 (LTS).** Records and sealed types, but no pattern matching for `switch`, which is the
feature that makes exhaustive message handling a compile-time guarantee. Rejected.

**Latest non-LTS.** Rejected: a portfolio project that stops building after six months is worse
than one built on a boring version.

## Consequences

- A fresh clone builds without a manually installed JDK; the first build downloads one.
- Contributors on an older JDK are unaffected — the launcher JVM only needs to be recent enough
  to start Gradle.
- Tooling that lags a new JDK release is a real risk for Error Prone in particular. This was
  verified before accepting the decision: Error Prone 2.50 and NullAway 0.13.8 run on JDK 25.
  Should a future JDK break them, the fallback is to keep the language level and pin the
  analyzers, not to drop the analyzers.
- `ToolchainSmokeTest` asserts the test JVM really is the pinned release, so a misconfiguration
  fails loudly and early instead of showing up much later as a confusing formatter error.
