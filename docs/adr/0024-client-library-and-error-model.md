# ADR-0024: A client that retries safely and admits what it does not know

- Status: Accepted
- Date: 2026-09-14

## Context

A client of a replicated store faces three problems that a client of a single server does not.
It has to find the leader, which moves. It has to retry, because leaders die mid-request. And every
retry risks running a command twice — which Phase 8's sessions exist to prevent, but only if the
client uses them correctly.

There is a fourth problem that is easy to get wrong in the other direction: sometimes a client
genuinely cannot know whether a command took effect. A library that hides that behind a generic
exception, or worse, reports failure for something that succeeded, produces histories that no
correctness checker can trust.

## Decision

**One RPC, `ClientService.Execute`, carrying the state machine's own encoded request and returning
its encoded result with the log index.** The node never interprets the bytes beyond asking the
state machine to validate them. The `StateMachine` port already takes bytes, so a counter or a lock
service would use the same RPC unchanged.

**Validation happens at the edge.** A command the state machine cannot decode used to throw on the
apply thread, which treats any exception as fatal — one malformed request from a client would have
stopped the node. `StateMachine.validate` now runs before the command is proposed, and the node
answers `INVALID_ARGUMENT`. `MalformedClientCommandTest` shows both the refusal and the crash it
prevents.

**Errors are gRPC status codes with a stated meaning.**

| Situation | Status | Client reaction |
|---|---|---|
| Not the leader, leader known | `UNAVAILABLE` + trailers `flotilla-leader-id`, `flotilla-leader-address` | go there immediately |
| Not the leader, no leader known | `UNAVAILABLE` | next node, back off |
| Node stopping or unreachable | `UNAVAILABLE` | next node, back off |
| Event queue full | `RESOURCE_EXHAUSTED` | same node, back off |
| Deadline passed | `DEADLINE_EXCEEDED` | next node, back off |
| Command cannot be decoded | `INVALID_ARGUMENT` | fail, never retry |

`UNAVAILABLE` is gRPC's retryable code, which is exactly right for a redirect. Session rejections
are not transport errors at all: they are answers from the state machine and arrive as a normal
result.

**`FlotillaClient` retries only what sessions make safe.** It opens a session on first use and
sends every command with `(clientId, sequence)`. A retry reuses the sequence, so a command whose
acknowledgement was lost is answered from the session cache rather than run again. Backoff is
exponential with full jitter, so clients recovering from the same outage do not arrive together.
A redirect with a leader hint is followed without waiting.

**Outcomes are reported honestly, in Jepsen's vocabulary.** Every command is recorded by a
`HistoryRecorder` as `INVOKE` followed by exactly one of `OK`, `FAIL` or `INFO`:

- `OK` — the state machine answered.
- `FAIL` — the command certainly did not take effect: it was invalid, or refused as stale.
- `INFO` — it may or may not have. The client gave up, or its session expired while a retry was
  pending. The caller receives `IndeterminateResultException`.

A session that expired on a request that cannot have run yet is renewed silently and the command
re-issued. A session that expired after an attempt that *may* have run is never retried under a new
session, because that is precisely the double execution sessions exist to prevent. Giving up is
always `INFO`, because `NOT_LEADER` and `UNAVAILABLE` do not reveal whether the command reached the
log first — and a checker can reason soundly about `INFO`, but not about a `FAIL` that was a lie.

## Alternatives considered

**A typed RPC per operation — `Put`, `Get`, `CompareAndSwap`.** More self-describing on the wire
and friendlier to tools like `grpcurl`. Rejected because it duplicates the command schema in
protobuf, ties the RPC layer to the key-value state machine, and gives up the canonical encoding
that ADR-0019 depends on. A typed facade can be added on top later without touching the log.

**Leader hint in the response body.** Would avoid trailers. Rejected because it forces every
response message to carry an error variant, and trailers are the standard gRPC mechanism for error
detail.

**Retry with a fresh sequence number.** Simpler client code. Rejected, and tested against: with a
fresh sequence, a compare-and-swap whose acknowledgement was lost runs twice and reports failure
for a swap that happened. Changing that one line makes the lost-acknowledgement test fail.

**Report giving up as `FAIL`.** Friendlier to callers who want a boolean. Rejected because it is
false in exactly the cases that matter, and Phase 11 checks histories for linearizability.

## Consequences

- The client is the only place the request encoding is produced, and the only place a history is
  recorded, so every run through it yields a history a checker can consume.
- Routing and retry logic is tested without a network, against a fake cluster backed by a real
  `KvStateMachine` that can lose acknowledgements after applying. The same client is then tested
  against three real nodes while the leader is killed mid-sequence; thirty compare-and-swap
  increments leave the counter at exactly thirty.
- A client that is silent longer than the session timeout pays for it with an explicit
  `IndeterminateResultException`, as `docs/consistency-model.md` already stated.
- Reads still go through the log. `ReadIndex` in Phase 11 makes them cheaper without changing this
  API.
