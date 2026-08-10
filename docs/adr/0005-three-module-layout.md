# ADR-0005: Three modules, with internal layering enforced by tests

- Status: Accepted
- Date: 2026-08-05

## Context

Flotilla has clear architectural layers: the pure consensus core, the durable storage, the state
machine, the transport, the node runtime, the client, the CLI, the simulation, the benchmarks and
the integration tests. Ten layers, and the obvious move is ten build modules.

That was the initial layout, and it was wrong for this project's size. Ten modules that hold one
file each produce a directory tree that is tiring to navigate and that signals ceremony rather
than structure. The question is what a module boundary actually buys that a package boundary does
not.

## Decision

Three modules:

| Module | Contains | Why it is separate |
|---|---|---|
| `flotilla-core` | the Raft algorithm | Zero dependencies. Nothing else may be on its compile classpath. |
| `flotilla-node` | storage, kv, transport.grpc, server, client, cli | Carries the runtime dependencies: gRPC, protobuf, picocli. |
| `flotilla-testing` | sim, bench, it | Carries JMH, Testcontainers and the fault-injection harness. |

Layering **between** these modules is enforced by the build: `flotilla-core` cannot reference the
others because they are not on its classpath.

Layering **inside** `flotilla-node` and `flotilla-testing` is enforced by ArchUnit rules that
express the same constraints over packages, and that fail the build like any other test.

## Alternatives considered

**Ten modules, one per layer.** Maximum separation of dependency classpaths, and every layer
independently publishable. Rejected as disproportionate: the extra isolation between, say,
`storage` and `transport` is hygiene, not architecture, and it is paid for on every single
navigation for the entire life of the project.

**One module, packages only.** The simplest tree, and — importantly — ArchUnit can enforce
package layering just as well as module layering can. What it cannot do is separate *dependency
classpaths*: a single module has one compile classpath, so gRPC, picocli and JMH would all be
visible to the consensus core. The claim "a dependency-free consensus core" would then be
something the README asserts and `build.gradle.kts` contradicts, and the core could not be
published as an embeddable library without dragging the whole runtime along.

**Two modules** (core plus everything else). Close, and defensible. Rejected because it puts
Testcontainers, Docker and JMH on the compile classpath of the code that ships.

## Consequences

- Three source trees instead of ten. Day-to-day navigation improves substantially.
- The one architectural claim that carries the project — a consensus core that *cannot* perform
  I/O because the classes are not reachable — is still enforced by the compiler, not by
  discipline.
- Internal layering now depends on ArchUnit rules being written and kept current. A layer
  violation surfaces as a failing test rather than as a compile error, which is later feedback
  and one step further from the IDE. This is the accepted cost, and it makes the architecture
  test in Phase 2 load-bearing rather than decorative.
- Splitting `flotilla-node` further later is possible but not free. If a package inside it grows
  its own consumers — a realistic case for `client` — promoting it to a module is the expected
  path.
