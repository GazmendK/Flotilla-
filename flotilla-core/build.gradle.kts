plugins {
    id("flotilla.java-conventions")
    id("flotilla.quality-conventions")
    id("flotilla.test-conventions")
}

description = "Pure Raft consensus state machine: no I/O, no threads, no wall clock."

// flotilla-core deliberately declares no dependencies at all -- not even a logging facade.
// The only thing on its compile classpath is JSpecify, which has no runtime behaviour.
//
// This is not minimalism for its own sake: purity is what makes the core deterministic, and
// determinism is what makes the simulation in flotilla-sim able to reproduce any failure from
// a seed. Before adding anything here, read docs/adr/0004-pure-deterministic-core.md.
// An ArchUnit test enforces this from Phase 2 onwards.
