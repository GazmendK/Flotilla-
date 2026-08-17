# ADR-0015: Store the hard state in two alternating slots

- Status: Accepted
- Date: 2026-08-10

## Context

`currentTerm` and `votedFor` carry a safety obligation that nothing else in the system does: they
must be durable *before* a vote response leaves the node. A server that answers a vote request,
crashes, and comes back without the record will vote a second time in the same term — and two
leaders in one term is how a committed entry disappears.

They are also written constantly. Every election writes them, and an unstable cluster holds a lot
of elections. So the write has to be both crash-safe and cheap.

The usual crash-safe pattern is write-to-temp, fsync, atomic rename, fsync the directory. That is
four operations, two of them directory-level, for a payload of about forty bytes.

## Decision

The hard state lives in a single fixed-size file holding **two 128-byte slots**. Each write
increments a generation counter and writes to the slot the generation selects; recovery reads both
slots and takes the one with the highest generation whose CRC32C is valid.

Writing is one positional write plus one `fsync` of an already-existing file. No temp file, no
rename, no directory sync on the hot path.

The safety argument is short enough to state in full: a write only ever touches one slot, so a
write interrupted at any point leaves the other slot exactly as it was. That slot is either the
previous state or, on the very first write, absent. Since the previous state is always a valid
state to resume from — a node may forget a vote it never told anyone about, but never one it
acknowledged — recovery is always correct.

## Alternatives considered

**Temp file plus atomic rename.** The standard answer, and correct. Rejected for this file because
of the cost per election and because `ATOMIC_MOVE` and directory sync both behave differently on
Windows, which would put a platform difference on the safety-critical path.

**Append-only, keeping every state ever written.** Simple, and it makes recovery "take the last
valid record". Rejected because the file grows without bound in exactly the situation where it is
written most — a cluster that cannot elect a leader — and would then need its own compaction.

**Three or more slots.** No additional safety: two is already enough for the argument above.

**Storing it as a record in the write-ahead log.** Tempting, since the machinery exists. Rejected
because it couples the vote to log truncation, and because the log's tail is exactly the region
that a crash makes unreadable.

## Consequences

- The node id in a vote is capped at 64 bytes. `NodeId` already enforces 64 characters, and both
  limits are stated where they are enforced.
- 35 bytes per slot are reserved, so the format has room to grow without changing the slot size.
- If both slots are unreadable the store reports no state, which is indistinguishable from a fresh
  node. That is the correct outcome: a node that cannot prove what it voted for must not claim to
  remember, and starting at term 0 is always safe.
- `FileStableStoreTest` corrupts the newest slot and asserts recovery falls back to the older one,
  which is the property the whole design exists for.
