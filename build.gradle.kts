plugins {
    base
}

description = "A distributed key-value store with a complete Raft implementation."

// The root project intentionally contains no build logic. Shared configuration lives in
// build-logic/ as convention plugins, so that "what applies to a module" is explicit at the
// module's own build file rather than injected from above by an allprojects/subprojects block.
