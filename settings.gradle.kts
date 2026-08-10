// Build logic lives in an included build rather than buildSrc: buildSrc invalidates the whole
// build on every change, an included build does not, and it can be published later if needed.
pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    // Resolves and downloads JDK toolchains (and the daemon JVM) from the Foojay Disco API,
    // so a fresh clone does not require a manually installed JDK 25.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    // Subprojects must not declare their own repositories; everything resolves from here.
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
}

rootProject.name = "flotilla"

// ---------------------------------------------------------------------------------------------
// Three modules. The split follows dependency classpaths, which is the one thing packages
// cannot express -- everything else (layering inside a module) is enforced by ArchUnit instead.
// See docs/adr/0005-three-module-layout.md.
//
//   core     <-  node  <-  testing
// ---------------------------------------------------------------------------------------------
include(
    // The Raft algorithm as a pure state machine. Zero dependencies, by design and by test.
    "flotilla-core",
    // Everything that turns the core into a running node: storage, kv, transport, server,
    // client, cli. Carries the heavy runtime dependencies (gRPC, protobuf, picocli).
    "flotilla-node",
    // Simulation, benchmarks and integration tests. Keeps JMH, Testcontainers and friends off
    // the classpath of anything that ships.
    "flotilla-testing",
)
