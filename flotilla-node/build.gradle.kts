plugins {
    id("flotilla.java-conventions")
    id("flotilla.quality-conventions")
    id("flotilla.test-conventions")
}

description = "Everything that turns the consensus core into a running node: durable storage, " +
    "the key-value state machine, the gRPC transport, the runtime, the client and the CLI."

dependencies {
    // `api` rather than `implementation`: the core's types (LogEntry, NodeId, Ready) appear in
    // this module's own public signatures, so consumers need them on their compile classpath.
    api(project(":flotilla-core"))
}

// This module holds several layers as packages. Their allowed dependency direction is enforced
// by an ArchUnit test from Phase 2 onwards rather than by module boundaries -- see
// docs/adr/0005-three-module-layout.md for why that trade was made.
//
//   storage, kv, transport.grpc  ->  core
//   server                       ->  core, storage, kv, transport.grpc
//   client                       ->  core, transport.grpc
//   cli                          ->  server, client
//
// Nothing here may be depended on by flotilla-core. That direction is enforced by the build.
