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

// The cluster tests here run real nodes on wall-clock time. Sharing the CPU with the other modules'
// test JVMs delays heartbeats past an election timeout and turns a busy runner into a failover.
tasks.named<Test>("test") {
    mustRunAfter(":flotilla-core:test", ":flotilla-node:test")
}
