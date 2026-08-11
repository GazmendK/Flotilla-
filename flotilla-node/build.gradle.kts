plugins {
    id("flotilla.java-conventions")
    id("flotilla.quality-conventions")
    id("flotilla.test-conventions")
}

description = "Everything that turns the consensus core into a running node: durable storage, " +
    "the key-value state machine, the gRPC transport, the runtime, the client and the CLI."

dependencies {
    api(project(":flotilla-core"))
}
