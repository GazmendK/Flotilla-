# ADR-0016: Prove durability by crashing at every write

- Status: Accepted
- Date: 2026-08-10

## Context

Storage code is where "we wrote it" and "it survived" are different statements, and the difference
only shows up when the machine dies at the wrong instant. Tests written by hand cover the crash
points somebody thought of, which is reliably not the one that matters.

The specific claim that needs proving is narrow and absolute: **an entry whose sync returned must
still be there after any crash, and the recovered log must always be a prefix of what was
written.** Anything weaker is not durability.

## Decision

A `FileIo` port sits under the storage layer, and the tests substitute an implementation that is
an in-memory file system with fault injection. It can:

- **crash at the *n*th physical write** — everything not synced is discarded, exactly as a power
  failure leaves it;
- **tear the *n*th write** — write half the bytes, then crash;
- **fail the *n*th write** — simulate a full disk.

`CrashConsistencyTest` counts how many physical writes a workload performs, then repeats the whole
workload once per write, crashing at that point, recovering, and asserting:

1. every entry whose `sync()` returned is still present,
2. the recovered log is a prefix of the intended one, entry for entry,
3. recovery is idempotent — the second recovery discards nothing.

The same is done for the hard state, where the assertion is that an acknowledged vote is never
forgotten and a recovered state is always one that was actually written.

## Alternatives considered

**Wrapping the real file system and killing the process.** More faithful, and what a Jepsen-style
test would do. Rejected as the primary mechanism: it is slow enough that crashing at *every* write
becomes impractical, and it cannot model "written but not synced is lost" without real power loss
hardware. The real file system is still exercised — every other storage test runs against it, on
three operating systems.

**Randomized crash points.** Cheaper, and it would have found the same bug eventually. Rejected
because exhaustive is both feasible here and strictly better: "we crashed at all 84 write points"
is a claim with no luck in it.

**Trusting the code review.** The bug below is the argument against.

## Consequences

- The tests run in memory, so a bug in `RealFileIo` itself is not caught here. That boundary is
  stated rather than blurred: `RealFileIo` is covered by the durability tests that run on real
  files on Linux, macOS and Windows.
- Crash points are physical writes, not arbitrary instants. A crash between two bytes of a single
  write is modelled by the tear fault instead.
- **This found a real bug on its first run.** A crash during the creation of a segment file left a
  file with no complete header, and recovery treated it as corruption and refused to start — a
  node that crashed at the wrong microsecond could never come back. A segment without a complete
  header provably contains no entries, because the header is synced before anything is appended,
  so the correct response is to discard it and continue. That distinction is easy to state once
  seen and easy to miss entirely without a test that lands exactly there.

## Amendment — 2026-09-14: the model was too kind

The original fault model had two gaps, and together they hid a bug of exactly the kind this ADR
exists to catch.

**Directory changes were durable immediately.** Creating or deleting a file took effect for good
the moment it happened. On a real Unix file system an unlink or a new directory entry survives a
power loss only after the *directory* has been fsynced; before that, a deleted file can come back.

**Crash points were writes only.** A crash could land between two writes but never between a file
sync and a directory sync, although a power loss can.

`FaultInjectingFileIo` now keeps the directory's durable state separately and restores exactly that
state on a crash, and every durability operation — write, file sync, delete, directory sync — is a
crash point. The name of the method that selects one, `crashAtWrite`, is kept for continuity.

**The stricter model found a bug in `truncateSuffixFrom` on its first run.** Truncation deleted the
later segments, shortened and synced the segment it kept, and only then synced the directory. A
crash in between brought the deleted segments back while the kept one was already short: segment
7 followed a segment ending at index 4, recovery reported a gap, and the node refused to start for
good. The fix is an ordering change and nothing else: delete the later segments and sync the
directory first, then shorten the kept segment. At every crash point the log is now either the
old one or the truncated one, and never has a gap. `everyCrashDuringTruncationRecovers` fails
without the fix.

Tolerating gaps during recovery was considered and rejected. A gap can also mean genuine
corruption, and silently dropping everything after it could discard acknowledged entries.
