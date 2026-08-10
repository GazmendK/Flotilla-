plugins {
    id("flotilla.java-conventions")
    id("flotilla.quality-conventions")
    id("flotilla.test-conventions")
}

description = "Deterministic simulation, benchmarks and integration tests."

dependencies {
    implementation(project(":flotilla-core"))
    implementation(project(":flotilla-node"))
}

// This module exists mainly to keep heavyweight verification dependencies -- JMH, Testcontainers,
// Toxiproxy, the fault-injection harness -- off the classpath of anything that ships.
//
// The `sim` package deliberately depends on flotilla-core only. It implements the same ports as
// the production runtime, so the simulation drives the shipped consensus code rather than a
// model of it. An ArchUnit test keeps `sim` from reaching into flotilla-node.
