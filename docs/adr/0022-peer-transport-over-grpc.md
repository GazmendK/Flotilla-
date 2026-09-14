# ADR-0022: Peers talk over gRPC through one one-way envelope

- Status: Accepted
- Date: 2026-09-14

## Context

The consensus core communicates by messages. `RaftNode` emits `AppendEntriesRequest`s and
`RequestVoteResponse`s into its `Ready`, and accepts them through `step()`. A response is not the
return value of a request — it is a separate message with its own `from` and `to`, which may arrive
at any time, arrive twice, or not arrive at all. The deterministic simulation already delivers
messages exactly that way, dropped, delayed, duplicated and reordered, and the five safety
invariants hold under it.

Running on real machines needs a transport. The choice of transport decides whether that
message-passing model survives contact with the network or is quietly replaced by something else.

## Decision

**gRPC over HTTP/2, with a single unary RPC, `RaftPeerService.Deliver`, carrying a `oneof`
envelope.** Every Raft message is one variant of `DeliverRequest`; the RPC's reply is an empty
acknowledgement that the envelope was accepted, never a Raft response. Raft responses travel back
as their own `Deliver` calls.

Around that, four rules:

- **Bounded in flight, drop when full.** Each peer has a fixed number of delivery slots. A message
  that finds none free is dropped and counted, not queued. Raft retransmits anything that matters,
  so a buffer would add memory growth under a dead peer and nothing else. Every message ends up
  counted exactly once as delivered, dropped or failed, which a test asserts over ten thousand sends
  to an unreachable address.
- **A delivery deadline shorter than the election timeout, checked at startup.** A deadline longer
  than the election timeout lets one hung delivery occupy a slot past the point where followers
  start an election. `FlotillaNode` refuses to start with such a configuration, and likewise refuses
  a message size limit smaller than the largest append batch a leader may send.
- **Reconnection is gRPC's, sharpened by a liveness hint.** gRPC reconnects with exponential,
  jittered backoff, and the transport does not re-implement it. The backoff grows with the length
  of an outage, though: measured once on loopback, a peer that returned on the same port after eight
  seconds of failed deliveries was reached again only after 1.8 seconds, and gRPC caps the wait at
  two minutes. A node that restarts on its old port would stay unreachable that long. So when any
  message arrives *from* a peer, the transport treats it as proof of life and cuts that peer's
  backoff short — but only if the channel is actually in `TRANSIENT_FAILURE`, because resetting the
  backoff also triggers name re-resolution, and doing that on every heartbeat would put DNS in the
  hot path. A restarted follower always sends pre-votes once heartbeats stop, so the hint arrives
  exactly when it is needed. A test bounds the reconnect after a ten-second outage below three
  seconds, and removing the reset makes it fail.
- **A peer's address can change.** The directory is consulted on every send, and a changed address
  replaces the channel. The cluster test restarts a follower on a new ephemeral port and waits for
  it to catch up.

The transport may therefore drop, delay, duplicate and reorder messages. It never corrupts one, and
it never blocks the caller.

## Alternatives considered

**One request/response RPC per message type.** The shape most gRPC services have, and what the
original plan listed. Rejected because it maps Raft onto the wrong model: `AppendEntries` would
return its response synchronously, the event loop would have to correlate replies with requests,
and a reply delayed past its deadline would be lost even though Raft would happily have used it.
The core already decoupled the two; the transport should not re-couple them.

**A bidirectional stream per peer pair.** Fewer HTTP/2 streams and natural ordering. Rejected for
now: the core does not need ordering, a stream introduces head-of-line blocking behind a large
append, and reconnecting a stream correctly is more code than a unary call. It remains the obvious
optimisation if a benchmark shows per-call overhead mattering.

**A buffered queue per peer.** Would smooth over brief hiccups. Rejected because a dead peer turns
it into unbounded memory growth, and because Raft already turns dropped messages into retries.

**Hand-rolled reconnect backoff.** Full control over the timing. Rejected because gRPC's backoff is
correct and jittered, and the only real gap — long outages on a fixed port — is closed by a hint
from an event the node observes anyway.

**Raw Netty framing.** Less overhead, no protobuf. Deferred, not rejected: listed after 1.0 as a
second transport, which is also what would justify the transport port existing at all.

## Consequences

- `docs/wire-protocol.md` documents the envelope, the delivery semantics and the error model.
- The schema is linted by `buf` on every push and checked for breaking changes on every pull
  request, so a change that would stop old and new nodes from understanding each other fails review
  rather than a rolling upgrade.
- A delivery slot is released when gRPC completes the call, successfully or not, so a slow peer
  holds at most `maxInflightPerPeer` slots and costs the leader nothing else.
- The liveness hint is a heuristic with a precise trigger, not a timer. A peer that returns but
  never sends anything is still reached through gRPC's own backoff, just later.
