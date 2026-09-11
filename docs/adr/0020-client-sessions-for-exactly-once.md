# ADR-0020: Client sessions, because Raft alone is only at-least-once

- Status: Accepted
- Date: 2026-09-06

## Context

Raft guarantees that a committed entry is applied once, in the same order, on every replica. It
guarantees nothing about how many times a *client request* becomes an entry.

The gap is one lost packet wide. A client sends a write, the leader commits it, the leader dies
before the acknowledgement is sent. The client sees a timeout, retries against the new leader, and
the write is committed a second time. Both entries are legitimate; both are applied exactly once
each. The client asked once and the effect happened twice.

For an idempotent `Put` nobody notices. For `CompareAndSwap` the damage is worse than a duplicate:
the retry sees the value the *first* attempt wrote, finds the expectation no longer holds, and
returns `false`. The caller is told it lost a race it actually won — and if that swap was a lock
acquisition, the true holder concludes it does not hold the lock.

## Decision

Client sessions as described in §6.3 of the Raft dissertation.

A client sends a `Register` request, which becomes a log entry. **Its client id is the log index of
that entry.** The id is therefore derived from replicated state rather than generated, so every
replica computes the same one without any coordination, and a restored snapshot yields the same ids
again.

Every subsequent request carries `(clientId, sequence)`. The state machine keeps, per client, the
last sequence number and **the encoded response to it**. On apply:

| Incoming sequence | Action |
|---|---|
| equal to the last one | return the cached response; execute nothing |
| greater than the last one | execute, cache the response |
| less than the last one | refuse with `STALE_SEQUENCE` |
| client unknown | refuse with `UNKNOWN_SESSION` |

Only the most recent response per client is kept, which bounds the table by the number of live
sessions rather than by traffic. Sessions expire after a configured number of **log entries**
without activity — never after wall-clock time, for the reason in
[ADR-0021](0021-no-wall-clock-in-the-state-machine.md).

The session table, the last responses and the applied index are all part of the snapshot. Leaving
any of them out produces a bug that only appears after a restore, which is why
`SnapshotRoundtripTest` and `SessionDedupTest` both assert it — removing the sessions from the
snapshot encoder makes them fail.

Requests may also be sent **anonymously**, with no session and no deduplication. That path exists
for internal and genuinely idempotent operations, and a test keeps it honest by demonstrating the
double execution it allows.

## Alternatives considered

**Idempotency keys on individual requests, with a time-bounded cache.** The usual HTTP approach.
Rejected because the cache would need its own expiry policy, and the only deterministic clock
available is the log index — at which point it *is* a session table, with a worse name and no
explicit lifecycle.

**Making every operation idempotent instead.** Works for `Put` and `Delete`, and is why those need
no session. It cannot work for `CompareAndSwap`, and a store without a conditional write is not one
you can build a lock or a leader election on.

**Deduplicating at the leader instead of in the state machine.** Cheaper, and wrong: a leader that
dies takes its deduplication table with it, and the next leader re-executes. Deduplication has to
live in the replicated state or it does not survive the failure it exists for.

**Caching every response rather than the last.** Would allow out-of-order retries. Rejected as an
unbounded memory leak keyed by client behaviour. One request in flight per session is the thesis's
model and a reasonable contract to publish.

## Consequences

- Exactly-once has a stated lifetime. A retry after the session expired gets `UNKNOWN_SESSION`
  rather than silently re-executing, so the guarantee degrades into an **explicit refusal**, never
  quietly into at-least-once. That boundary is written down in
  [`docs/consistency-model.md`](../consistency-model.md).
- A client must retry the *identical* request, same sequence included. A retry with a fresh sequence
  is a new request and will execute again — correctly, by the rules above.
- Registration costs a round of consensus. Clients are expected to register once and keep the id.
- The response cache stores encoded bytes, not objects, so the cached answer to a retry is
  byte-identical to the original and the session table serializes without a second format.
- Removing the cache lookup makes six tests fail, including both end-to-end cases through
  `RaftServer`. The mechanism is load-bearing rather than decorative.
