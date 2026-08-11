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
