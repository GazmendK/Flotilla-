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
  logbase                         64 bytes, two 32-byte slots: where the log begins
  00000000000000000001.wal        segment starting at log index 1
  00000000000000000513.wal        segment starting at log index 513
  snapshot-00000000000000000512.snap   state through log index 512
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

## Log base — 64 bytes, two slots

Compaction discards a prefix of the log that a snapshot already covers. What it must not discard is
the **term of the last compacted entry**: the leader sends it as `prevLogTerm` in the next append,
and a follower checks it. The log base records that one entry's position.

The file uses the same two-slot scheme as the hard state, and for the same reason: an interrupted
write damages only the slot being written. It is written and synced before any segment is
deleted, so it is the commit point of every compaction.

Each slot:

| Offset | Size | Field |
|---|---|---|
| 0 | 8 | generation, starts at 1 |
| 8 | 8 | base index — the last compacted entry, 0 for an uncompacted log |
| 16 | 8 | base term — that entry's term |
| 24 | 4 | CRC32C of bytes 0–23 |
| 28 | 4 | reserved, zero |

A missing file, or two unusable slots, means base `(0, 0)`: nothing compacted. The log's first index
is always `base index + 1`, regardless of which segment files happen to still exist.

### Compacting

`compactTo(index)` writes the new base and syncs it, then deletes every segment that lies wholly at
or below that index, then syncs the directory. A segment that straddles the base is kept; the
entries in it that sit below the base are unreachable and simply stay on disk until the whole
segment falls behind a later base.

### Resetting to a snapshot

When a follower installs a snapshot its log does not match, the whole log goes, and the order is the
entire correctness argument:

1. truncate every entry, and sync — the existing, crash-tested truncation
2. write the new base, and sync
3. delete the old segments, create one starting at `base index + 1`, sync the directory

Writing the base first instead would leave a window in which the new base sits beside old segments
still holding a **divergent suffix beyond it**, and recovery would accept those entries as the log.
Deleting first would leave the old base beside a log with its committed prefix gone. With this
order, a crash at any point leaves either the old log or the new one — never a mixture.
`everyCrashDuringResetNeverResurrectsTheOldLog` and `everyCrashDuringCompactionRecovers` crash at
every durability operation, and swapping either order makes them fail.

## Snapshot files

A snapshot is one file named `snapshot-<20-digit last included index>.snap`, so the newest is the
last one a plain `ls` prints.

| Offset | Size | Field |
|---|---|---|
| 0 | 4 | magic `0x464C5350` |
| 4 | 4 | format version, currently 1 |
| 8 | 4 | body length in bytes |
| 12 | 4 | CRC32C of the body |
| 16 | *n* | body |

The body holds the last included index and term, the cluster configuration the snapshot was taken
under, and the state machine's own encoding of its state:

```
int64  last included index
int64  last included term
int32  voter count,   then per voter:   int32 length + UTF-8 id
int32  learner count, then per learner: int32 length + UTF-8 id
int32  payload length, then the payload
```

The last included index and term are not decoration: they become `prevLogIndex` and `prevLogTerm`
for the first `AppendEntries` after the snapshot. Lose the term and the consistency check fails
forever, and the follower loops between snapshot and rejection.

Decoding is total. Every length and count is bounded before anything is allocated, and the body is
checked against its CRC before it is parsed at all — `SnapshotStoreTest` flips every bit of every
byte of a snapshot file and asserts each one is caught.

### Writing one

```
write   snapshot-<index>.snap.tmp
fsync   the file
rename  .snap.tmp -> .snap          (atomic)
fsync   the directory
```

A crash at any point leaves either the previous snapshot or the new one, never half of either: the
final name only ever appears on a file whose bytes are already durable. `CrashConsistencyTest`
crashes at every one of those operations and runs the whole schedule twice — once against a
filesystem where a directory entry becomes durable only at a directory fsync, and once against one
that journals metadata eagerly. Writing straight to the final name passes the first model and fails
the second, which is the case the rename exists for.

Temporary files left behind by a crash are deleted on start. A snapshot that fails its checksum is
skipped and the one before it is used, which is why more than one is kept.

### Retention

The newest two snapshots are kept by default. Keeping one is allowed and keeping none is not: a
follower that falls behind a compacted prefix can only be caught up from a snapshot, so throwing the
last one away while the log is already compacted would strand it for good.

## Recovery

On open, each segment is scanned from its header forwards. Scanning stops at the first record that
is not fully readable:

- fewer than 8 bytes remain — end of log
- the length field is zero or implausible — end of log, which is what preallocated space reads as
- fewer bytes remain than the length claims — a torn write
- the checksum does not match — a torn write or corruption

Everything from that point on is discarded and the file is truncated. The number of discarded
bytes is reported by the store so that operators, and tests, can see it rather than guess.

Segments that lie wholly at or below the log base are left over from a compaction or reset that
crashed before deleting them. They are deleted on open. A remaining first segment that starts
**after** `base index + 1` would mean entries between the base and the log are missing, and is
refused as corruption.

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

How often the log is forced is a policy:

| Policy | Log | Hard state | For |
|---|---|---|---|
| `BATCHED` (default) | forced only when `sync()` is called | forced on every write | a running node: the event loop calls `sync()` once per batch, which is what makes group commit physical |
| `ALWAYS` | forced after every append and truncation, and on `sync()` | forced on every write | a store used without an event loop to batch for it |
| `NEVER` | never forced, not even on `sync()` | never forced | benchmarks and tests only |

`ALWAYS` in a running node defeats group commit completely: the consensus core appends each
proposal as it arrives, so each append pays its own `fsync` before the event loop has anything to
batch. Structural durability points — rolling to a new segment, the truncation inside a reset, the
log base — are forced under both `ALWAYS` and `BATCHED`, because the crash-safety arguments above
depend on them.

Any number measured under `NEVER` is meaningless without saying so.
