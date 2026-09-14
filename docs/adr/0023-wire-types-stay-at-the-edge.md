# ADR-0023: Wire types stay at the edge

- Status: Accepted
- Date: 2026-09-11

## Context

Protobuf generates a Java class for every message in the schema. The path of least resistance is to
use those classes everywhere: the core could accept `dev.flotilla.wire.v1.AppendEntriesRequest`
directly, and there would be no mapping layer to write or maintain.

That path couples three things that change for different reasons. The consensus core changes when
the algorithm changes. The wire schema changes when compatibility between versions of running nodes
requires it — and must never change in ways that break a rolling upgrade. The generated classes
change whenever the protobuf compiler does. Letting generated types into the core would make the
core's API a function of all three.

There is also a module boundary that already makes the choice for part of the system:
`flotilla-core` has **zero dependencies**, enforced by the compiler. It cannot see protobuf even if
it wanted to.

## Decision

**Generated protobuf types exist only inside `dev.flotilla.transport.grpc`.** Everything else speaks
the core's own records.

`MessageCodec` is the single translation point. Every `RaftMessage` subtype maps to one variant of
the `DeliverRequest` envelope and back, and the mapping is explicit field by field — no reflection,
no generic converters. Two rules make the boundary safe:

- **Decoding is total.** Whatever arrives, the result is either a valid domain message or a
  `WireFormatException`. A value the domain would reject — a negative index, an empty node id, an
  unknown entry type — is caught in the codec and reported as a wire error, never allowed to become
  an `IllegalArgumentException` thrown later from somewhere that does not expect it.
- **The recipient is checked at the edge.** `RaftNode.step` throws when a message is addressed to a
  different node, and the event loop treats any exception as fatal. A single misaddressed message
  from the network would therefore stop a node for good. `GrpcPeerService` refuses such a message
  with `FAILED_PRECONDITION` before anything reaches the event queue. `MisaddressedMessageTest` holds
  both halves: refused over the network with the node still running, and — delivered directly,
  bypassing the check — the event loop stopped.

The boundary is mechanical, not a convention. `NodeLayeringTest` fails the build if any class
outside the transport depends on `dev.flotilla.wire..`, and if the state machine or the storage
layer depends on `io.grpc` or `com.google.protobuf` at all.

Byte payloads cross the boundary with exactly one copy in each direction. `Bytes.toByteArray()`
returns a fresh array that nothing else references, so it can be handed to
`UnsafeByteOperations.unsafeWrap` without a second copy; in the other direction
`ByteString.toByteArray()` is a fresh array that `Bytes.wrap` takes ownership of. Neither side ever
sees the other's internal buffer.

## Alternatives considered

**Use the generated types throughout.** No mapping code. Rejected on the coupling argument above,
and because `flotilla-core` cannot depend on protobuf without abandoning its zero-dependency
guarantee — which is what lets the deterministic simulation run the production consensus code
unchanged.

**A reflective or annotation-driven mapper.** Less code than hand-written mapping. Rejected because
the mapping is exactly where validation has to happen, and a generic mapper would either skip it or
hide it. Eleven message shapes do not justify a framework.

**Validate in the core instead of at the edge.** Make `RaftNode.step` ignore misaddressed messages
rather than throw. Tempting, and possibly right eventually. Rejected for now because inside the
process a misaddressed message is a programming error, and an exception is the correct response to
a programming error — the simulation depends on that to catch routing bugs. The network is the one
place where such a message is not a bug but an input, and that is where it is handled.

## Consequences

- Adding a field to a message means touching the record, the schema and the codec. The codec test
  asserts that every permitted subtype of `RaftMessage` has a wire form, so a new message type fails
  there before it fails anywhere else.
- The wire schema can evolve independently of the domain model: fields can be added, deprecated or
  reserved without the core noticing.
- The core stays free of any serialization concern, and the simulation keeps testing the exact code
  that runs in production.
