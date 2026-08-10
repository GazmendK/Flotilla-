# ADR-0003: Use Gradle with convention plugins instead of Maven

- Status: Accepted
- Date: 2026-08-05

## Context

Flotilla is a multi-module build that needs a shared, non-trivial configuration: a pinned toolchain,
warnings-as-errors, Error Prone with NullAway, Spotless, reproducible archives, and later
protobuf generation, JMH, and integration tests that start real processes.

The question is not "which build tool is more popular" but "how does shared configuration get
applied to modules, and can a reader tell what applies to a given module".

## Decision

Gradle 9 with the Kotlin DSL, plus:

- **Convention plugins in an included build** (`build-logic/`), not `buildSrc`. Changing
  `buildSrc` invalidates the entire build; an included build does not. It can also be published
  independently later.
- **A version catalog** (`gradle/libs.versions.toml`) as the single place where any third-party
  version is declared, including the plugin marker artifacts used by `build-logic`.
- **No `allprojects`/`subprojects` blocks.** Each module's build file lists the conventions it
  applies. Configuration injected from a parent is invisible at the place it takes effect.
- **The configuration cache enabled from the first commit**, so that no later plugin addition
  can quietly make the build cache-incompatible without anyone noticing.

## Alternatives considered

**Maven.** Genuinely appealing: declarative, boring, and universally understood. Rejected mainly
because shared configuration in Maven is inheritance from a parent POM, and inheritance plus
profiles is a poor fit for "this module is analyzed strictly and has no dependencies, this one
runs JMH and starts Docker containers". Toolchain support and incremental behaviour for a build
of this shape are also weaker.

**Bazel.** Better at very large builds and hermetic execution than either. Rejected as
disproportionate: the setup cost and the ecosystem friction for JVM libraries would consume time
that belongs in the consensus implementation.

**Gradle with Groovy DSL.** Rejected: no static typing, no IDE completion for the accessors, and
the Kotlin DSL is the documented default.

## Consequences

- A slightly higher entry cost for a reader who knows Maven but not Gradle. Mitigated by keeping
  `build-logic` small and heavily commented, and by module build files that are five lines long.
- Convention plugins compile as Kotlin, so a mistake in the build fails at compile time rather
  than at task execution.
- The configuration cache constrains what build logic may do (no `project` access at execution
  time). This is a real restriction and an intentional one.
