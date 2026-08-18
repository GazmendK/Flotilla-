# On-disk format

Everything here is byte-exact and verifiable. If this page and the code ever disagree, the code is
right and this page is a bug.

All integers are **big-endian**. All checksums are **CRC32C** (Castagnoli), which is hardware
accelerated on every CPU this will realistically run on.

## Directory layout

```
<data-dir>/
  LAYOUT                          text: version=1
  hardstate                       256 bytes, two 128-byte slots
  00000000000000000001.wal        segment starting at log index 1
  00000000000000000513.wal        segment starting at log index 513
```

Segment file names are the zero-padded 20-digit index of their first entry, so lexicographic
order is index order and a plain `ls` shows the log in sequence.

`LAYOUT` is checked on open. A directory written by a version this build does not understand is
refused rather than misread — losing the log to a confident misparse is worse than refusing to
start.

## Segment header — 24 bytes

| Offset | Size | Field |
|---|---|---|
| 0 | 8 | magic `46 4C 4F 54 57 41 4C 31` ("FLOTWAL1") |
| 8 | 4 | format version, currently 1 |
| 12 | 8 | index of the first entry in this segment |
| 20 | 4 | CRC32C of bytes 0–19 |

## Record framing

Every record after the header is framed:

| Offset | Size | Field |
|---|---|---|
| 0 | 4 | body length in bytes, 1 … 67 108 864 |
| 4 | 4 | CRC32C of the body |
| 8 | *n* | body |

The length is validated **before** anything is allocated. A corrupted length field is the classic
way to turn a bad disk into an `OutOfMemoryError`, and the guard is why the fuzz target for this
decoder is worth having.

### Record body: log entry

| Offset | Size | Field |
|---|---|---|
| 0 | 1 | record type, 1 = log entry |
| 1 | 8 | term |
| 9 | 8 | index |
| 17 | 1 | entry type: 1 = NORMAL, 2 = NOOP, 3 = CONFIGURATION |
| 18 | 4 | payload length |
| 22 | *n* | payload |

Entry types are written as explicit codes rather than enum ordinals. An ordinal is a number whose
meaning changes when somebody reorders an enum, and reordering an enum is not the kind of change
anyone expects to corrupt a disk format.

### Worked example

A fresh log holding one entry — term 2, index 1, payload `"hi"` — is 56 bytes:

```
0000  46 4c 4f 54 57 41 4c 31   magic "FLOTWAL1"
0008  00 00 00 01               format version 1
0012  00 00 00 00 00 00 00 01   first index 1
0020  56 8b 51 63               header CRC32C
0024  00 00 00 18               record body length: 24
0028  b5 ec ee 9f               body CRC32C
0032  01                        record type: log entry
0033  00 00 00 00 00 00 00 02   term 2
0041  00 00 00 00 00 00 00 01   index 1
0049  01                        entry type: NORMAL
0050  00 00 00 02               payload length 2
0054  68 69                     "hi"
```

## Hard state — 256 bytes, two slots

`currentTerm` and `votedFor` must be durable *before* a vote response leaves the node, and they
change on every election. Making that cheap and torn-write-proof at the same time is the whole
design problem.

The file holds two fixed 128-byte slots. Writes alternate between them, driven by a generation
counter; recovery picks the slot with the highest generation **and** a valid checksum. A write
interrupted halfway can therefore only damage the slot being written, and the previous one is
still intact and still valid — no rename, no temp file, no directory sync on the hot path.

Each slot:

| Offset | Size | Field |
|---|---|---|
| 0 | 8 | generation, starts at 1 |
| 8 | 8 | currentTerm |
| 16 | 8 | commitIndex |
| 24 | 1 | length of the vote in bytes, 0 = no vote |
| 25 | 64 | node id of the vote, UTF-8, zero padded |
| 89 | 35 | reserved, zero |
| 124 | 4 | CRC32C of bytes 0–123 |

A slot with generation 0, an unparseable vote, or a failing checksum is ignored. If both slots are
unusable the store reports no state at all, which is indistinguishable from a fresh node — the
correct outcome, because a node that cannot prove what it voted for must not claim to remember.

After persisting term 2 with a vote for `n2` and commit index 1, the second slot is written and
the first is still untouched:

```
0128  00 00 00 00 00 00 00 01   generation 1
0136  00 00 00 00 00 00 00 02   currentTerm 2
0144  00 00 00 00 00 00 00 01   commitIndex 1
0152  02                        vote length 2
0153  6e 32                     "n2"
0155  ... zero padding and reserved ...
0252  fa 95 d1 33               CRC32C
```

## Recovery

On open, each segment is scanned from its header forwards. Scanning stops at the first record that
is not fully readable:

- fewer than 8 bytes remain — end of log
- the length field is zero or implausible — end of log, which is what preallocated space reads as
- fewer bytes remain than the length claims — a torn write
- the checksum does not match — a torn write or corruption

Everything from that point on is discarded and the file is truncated. The number of discarded
bytes is reported by the store so that operators, and tests, can see it rather than guess.

A gap **between** segments is different: it can only mean a segment file was lost, and no amount
of truncation makes the log correct again. That is refused loudly.

A segment file **shorter than its 24-byte header** is a third case, and not an error either. The
header is written and synced before any entry is appended, so a file without a complete header
provably contains no entries; it is the residue of a crash during segment creation. Such a file is
deleted and the store continues. Treating it as corruption instead means a node that crashed at
exactly the wrong microsecond can never start again — which is what the crash test found on its
first run.

A file **at least as long as the header** whose magic, version or header checksum does not match is
corruption, and is refused.

### What this does not distinguish

A checksum failure at the tail could be a torn write from a crash, or it could be a bad sector in
the middle of an otherwise complete log. Scanning stops at the first failure either way, so the
two are indistinguishable from the file alone — as they are in every write-ahead log of this
shape. Silent data corruption in a *committed* prefix is detected on read and reported as
corruption rather than served, but repairing it from a peer is a roadmap item, not a feature.

## Durability

`FileHandle.sync()` maps to `FileChannel.force(false)`, which syncs the data without forcing a
metadata update. Creating or deleting a file additionally needs the **directory** to be synced,
otherwise the entry may be absent after a power failure even though the file's contents are safe.

Directory sync is not portable. On Linux it is opening the directory and calling `force`. On
Windows that throws, and the first time it does the storage layer logs once and continues,
relying on the file system's own metadata ordering. This is why the CI matrix includes Windows:
the difference is real, it is silent, and it only shows up in the platform nobody tested.

`fsync` can be disabled entirely (`FsyncPolicy.NEVER`). That is for benchmarks and tests only,
and any number measured that way is meaningless without saying so.
