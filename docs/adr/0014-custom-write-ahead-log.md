# ADR-0014: Write the log format instead of embedding a storage engine

- Status: Accepted
- Date: 2026-08-10

## Context

The Raft log needs to survive a power failure. The obvious answer is an embedded storage engine —
RocksDB, or a pure-Java equivalent — which has solved durability, checksums and recovery already,
and by people who have been doing it longer.

## Decision

Flotilla writes its own segmented write-ahead log: files named after the index of their first
entry, records framed with a length and a CRC32C, recovery by forward scan with truncation of an
unreadable tail.

Two reasons, in order of honesty.

**The access pattern genuinely differs.** A Raft log is append-only at one end, truncated at both,
read sequentially, and never queried by key. An LSM tree solves point lookups, range scans and
compaction of overwritten keys — none of which occur here — and pays for them with write
amplification and background compaction that competes with the very fsync latency the consensus
protocol is waiting on. The right data structure for "append and read forwards" is a log.

**Understanding durability is the point of this project.** Delegating the one component where
`write` and "the data survives" are different things would remove the part worth building. That is
a legitimate reason for a portfolio project and a bad one for a product, and the difference is
stated rather than hidden.

## Alternatives considered

**RocksDB via JNI.** Battle-tested, and the option a production system should take seriously.
Rejected: a native dependency, platform-specific binaries, and it would answer none of the
questions this project exists to answer.

**An embedded pure-Java store such as MapDB or a B-tree.** No native dependency, but the same
structural mismatch, plus a dependency in the durability path that would have to be understood
just as deeply to trust it.

**Files without a checksum**, relying on the file system. Rejected outright: a torn write and a
valid record are indistinguishable without one, and "the tail is truncated" and "the log is
corrupt" have very different correct responses.

## Consequences

- The format is documented byte-exactly in `docs/storage-format.md`, with a real hexdump, so an
  independent reader could write a compatible parser.
- Segment size, fsync policy and layout version are all explicit configuration. The layout version
  is checked on open so a future format change refuses to be misread.
- The **state machine** is a separate question. An embedded engine there would be a good fit and
  the `StateMachine` port is shaped to allow it; nothing in this decision argues against it.
- Reading an entry decodes it from the file every time. There is no term cache and no memory-mapped
  fast path yet, because there is no measurement yet that says one is needed. Phase 15 is where
  that gets decided with numbers instead of intuition.
