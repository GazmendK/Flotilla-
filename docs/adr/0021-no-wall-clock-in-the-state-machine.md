# ADR-0021: The state machine never reads a clock

- Status: Accepted
- Date: 2026-09-06

## Context

Session expiry is the first feature in Flotilla that wants to know what time it is. Key TTLs,
leases and expiring locks would all want the same thing later.

The obvious implementation reads `System.currentTimeMillis()` when the entry is applied. It is also
the single most dangerous line that could be written in this file.

A replica does not apply an entry at the same instant as its peers. A follower that was partitioned
applies entry 5000 an hour after the leader did. A replica restarted this morning replays the whole
log in seconds, applying entries whose original wall-clock moments were spread over weeks. If
expiry compares a stored deadline against "now", each replica reaches a different verdict about
whether a session still exists — and whether a session exists decides whether a request executes or
is refused. That is not a timing discrepancy; it is the replicated state itself diverging.

The failure mode is the worst kind: silent, delayed, and only visible much later as two replicas
producing different snapshots.

## Decision

**The state machine reads no clock at all.** Anything time-dependent uses replicated values.

For session expiry, the measure is the **log index**. A session is dropped when
`currentIndex - lastActiveIndex > sessionTimeoutEntries`. Every replica applies the same entries at
the same indices, so every replica expires exactly the same sessions at exactly the same point in
the log, whenever it happens to get there.

For anything that genuinely needs a timestamp — key TTLs, if they are ever added — the rule is that
**the leader writes its own timestamp into the log entry**, and every replica uses that value.
Replicated time is time all replicas agree on; local time is not.

The same discipline already governs `flotilla-core`, where an ArchUnit rule forbids the consensus
code from touching a clock, threads, I/O or unseeded randomness. This ADR extends the reasoning to
the state machine, where it is enforced by design and by the determinism tests rather than by a
rule — the state machine lives in `flotilla-node`, where clocks are legal for other purposes.

## Alternatives considered

**Wall clock with generous tolerances.** "Sessions last an hour, replicas are within seconds of each
other, it will be fine." Rejected because it is only fine until a partition, a long GC pause, a
restart with log replay, or a clock adjustment — and the resulting divergence surfaces weeks later
as a snapshot mismatch with no obvious cause.

**A logical clock advanced by heartbeats.** More elegant than an index count and closer to real
time. Rejected as more machinery for no benefit here: the index is already a perfectly ordered,
already-replicated counter, and session lifetime measured in entries is arguably the more useful
unit — a busy cluster reclaims idle sessions sooner, which is what you want.

**Leader-stamped timestamps for sessions too.** Correct, and the mechanism reserved for TTLs.
Rejected for sessions because it adds a field to every request for a policy the log index expresses
just as well.

## Consequences

- Session lifetime is measured in log entries, not seconds. The operational meaning depends on
  traffic: 10 000 entries is a long time on an idle cluster and a short one under load. That is
  stated in [`docs/consistency-model.md`](../consistency-model.md) rather than hidden behind a
  duration that would be a lie.
- `SessionDedupTest` asserts expiry by driving the index forward with filler entries, with no sleep
  anywhere. The test is instant and cannot flake, which is a direct consequence of the decision.
- Adding a TTL feature later means adding a timestamp field to the log entry, not calling a clock.
  The ADR exists so that is the obvious move rather than a rediscovery.
- A future `Lease` or `Expire` command must be reviewed against this rule specifically. There is no
  mechanical check for it inside `flotilla-node`; the determinism tests would catch a divergence
  only if the test happened to apply the same log at two different moments.
