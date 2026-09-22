# Operations

What an operator has to know to run Flotilla, change its members, back it up, and get it back
after something went wrong. Everything here is checked against the code; if the two disagree, the code is right and this
page is a bug.

## The data directory

One directory per node, never shared between nodes:

```
<data-dir>/
  LAYOUT                                 storage layout version
  hardstate                              term, vote, commit index
  logbase                                where the log begins
  00000000000000000001.wal               log segments
  snapshot-00000000000000004096.snap     state through log index 4096
```

The layout is described byte for byte in [storage-format.md](storage-format.md). Nothing in the
directory is safe to edit by hand, and nothing in it is safe to copy between nodes: the hard state
holds this node's vote, and two nodes that share a vote can elect two leaders in one term.

## Backing up a cluster

**There is no online backup yet.** Copying files out of a running node is not safe: a snapshot and a
compaction can happen between two `cp` commands, and the copy then holds a snapshot and a log that
never existed together. Depending on the order, that is either a gap between the snapshot and the
first segment or a `logbase` that disagrees with the segments beside it.

What is safe is backing up a **stopped follower**. Every replica holds the same committed state, and
a three-node cluster keeps its quorum with one node down:

```bash
# 1. stop one follower, not the leader
# 2. copy its whole data directory
cp -r <data-dir> <backup-dir>
# 3. start it again; it catches up from the leader
```

The copy is consistent because nothing wrote to it while it was taken. Back up one node, not all of
them: three copies of the same committed state cost three times the space and prove nothing extra.

## Restoring

**One node lost, quorum still there.** Restart it on its old data directory if the directory
survived. It catches up from the leader — from the log if the entries still exist, from a streamed
snapshot if they do not, which is the path `InstallSnapshotIT` exercises.

If the directory itself is gone, do not start the node empty under its old id. A node that loses
its disk also loses the vote it cast in the current term, and in a narrow window it could vote a
second time. PreVote and the leader lease make that window small, not empty. The safe procedure is
[replacing the node](#replace-a-node): remove the old id, and add the machine back under a new one.

**Quorum lost.** Restore from a backup:

1. Stop every node that is still running.
2. Put the backup back onto **the node it was taken from**, unchanged, `hardstate` included.
3. Wipe the data directories of every other node.
4. Start all of them.

The restored node has the only non-empty log, so it is the only one the election restriction lets
win. It becomes leader and refills the others from its log or its snapshot. Keeping `hardstate` is
safe here precisely because every node that could remember a vote in that term has been wiped.
Never copy a backup onto a *different* node: the hard state holds the original node's vote.

This loses every entry committed after the backup was taken. A backup is a point in time.

## Changing the members

Members are changed one at a time, through the leader, with `FlotillaAdmin`. Every call finds the
leader on its own, so any node will do as a starting point. The `flotillactl` commands arriving in
Phase 14 wrap exactly these calls.

```java
try (FlotillaAdmin admin = FlotillaAdmin.connect(List.of(anyNode), ClientConfig.defaults())) {
    System.out.println(admin.describe());
}
```

`describe()` asks the leader and shows the configuration, whether it has committed, the leader's
commit and applied index, and for every learner how its catch-up rounds are going. `describe(node)`
asks one particular node for its own view. The answers to "is it safe yet?" below all come from it.

A new node must be resolvable by the leader's `PeerDirectory` before it is added: the configuration
names nodes, not machines, and a leader that cannot reach a learner cannot catch it up. The request
is refused if it cannot.

### Add a node

1. Start the new node with the current members as its initial configuration. It is not a member yet,
   so it waits: it neither votes nor campaigns.
2. Add it as a learner: `admin.addLearner(n4)`. It is replicated to from now on and counts for
   nothing.
3. Wait until it has caught up: `admin.awaitCaughtUp(n4, Duration.ofMinutes(5))`. A learner counts as
   caught up once a replication round — everything the leader had when the round began — finished
   within an election timeout, and the current round has not already taken longer.
4. Promote it: `admin.promote(n4)`. The quorum grows the moment the leader appends the change.

Skipping the learner stage is not possible, on purpose. A voter that joins empty raises the quorum
before it can contribute to it: a three-node cluster that becomes four with the new node still
catching up tolerates no failure at all until it has.

### Remove a node

1. If the node leads, hand leadership away first: `admin.transferLeadership(n2)`. Not required — a
   leader that removes itself hands over on its own — but a planned handover costs nothing.
2. Remove it: `admin.remove(n1)`. The call answers once the removal has committed.
3. Wait until `describe()` names some other node as leader, then stop the removed node. A removed
   leader is still handing over for a moment after the call returns; stopping it then brings back a
   full election timeout without a leader.
4. Delete its data directory. It must never rejoin under the same id with that state.

The request is refused if the voters that would remain, and that the leader has heard from within an
election timeout, would not form a majority of the new configuration. Removing the node that is
actually down is fine; removing a healthy one while another is down would stop the cluster, and the
refusal says so with the numbers.

### Replace a node

For a node whose disk is gone, or a machine being swapped:

1. [Add](#add-a-node) the new machine under a **new** id and promote it.
2. [Remove](#remove-a-node) the old id.

Adding before removing keeps the cluster's fault tolerance during the swap. In a three-node cluster
with the old node dead, the order matters even more: removing first leaves two voters, a quorum of
two, and no room for another failure while the replacement catches up.

### Take a node down for maintenance

Rolling restarts need no membership change:

1. If the node leads, `admin.transferLeadership(other)`. The target is brought up to date and told to
   campaign at once; the call answers when it leads. Writes are refused for the moment it takes.
2. Stop the node, do the work, start it again on its data directory. It catches up from the log or a
   snapshot.
3. Wait until it has caught up before taking down the next one: `admin.describe(node).appliedIndex()`
   has reached the `commitIndex()` the leader reported when the node came back.

### When a change is refused

| Refusal | What it means | What to do |
|---|---|---|
| "has not caught up" | the learner's last round took longer than an election timeout, or the current one already has | wait; the leader retries for three election timeouts, then call `awaitCaughtUp` |
| "only N of them have been heard from recently" | the change would leave no reachable majority | bring the unreachable voters back, or remove the one that is down first |
| "not committed an entry of its own term", "only one change may be in flight", "being handed to" | a condition that clears itself | nothing — the leader waits for it before answering |
| "at least one voter" | the last voter cannot be removed | add another voter first |
| "did not take over within an election timeout" | a handover was abandoned; the old leader kept leading and takes writes again | try again, or hand over to a voter that is less busy |

## How large do snapshots get?

A snapshot is the serialized state machine, not the log. For the key-value store that is roughly

```
sum over keys of (key bytes + value bytes + 8 bytes of framing)
  + sum over open sessions of (28 bytes + the cached response)
```

so 100,000 keys with 1 KiB values is about 100 MB. The log between two snapshots is bounded by the
policy, which triggers on whichever comes first:

| Setting | Default | What it means |
|---|---|---|
| `SnapshotPolicy.entriesBetweenSnapshots` | 10,000 | at most this many entries accumulate before a snapshot |
| `SnapshotPolicy.bytesBetweenSnapshots` | 64 MiB | at most this much applied data accumulates |
| `ServerConfig.snapshotsRetained` | 2 | how many snapshot files are kept |

Disk use settles at roughly `retained × snapshot size + the log since the newest snapshot`. Raising
`entriesBetweenSnapshots` trades disk for fewer snapshots; lowering it trades work for a shorter
restart.

## What to watch

`RaftServer` exposes these directly, and Phase 13 turns them into metrics:

| Reading | Healthy | Wrong |
|---|---|---|
| `snapshotsTaken()` | climbing slowly | flat while the log grows: the policy never fires |
| `logCompactions()` | tracks `snapshotsTaken()` | flat while snapshots are taken: compaction is being refused |
| `snapshotRestores()` | one per restart once a snapshot exists | climbing between restarts: this node keeps falling behind the compacted prefix |
| `longestSnapshotApplyPause()` | microseconds | milliseconds: the session table, which is still copied, has grown very large |
| `firstLogIndex()` | rises over time | stuck at 1 while the disk fills: nothing is being compacted |

A node whose `snapshotRestores()` climbs without being restarted is a node that cannot keep up. Either its disk is
slower than the leader's write rate, or `entriesBetweenSnapshots` is too small for the replication
lag it sees.

## Things that go wrong

**"The disk is full and nothing is being compacted."** Compaction is refused unless a snapshot
already covers the entries, and a snapshot is refused if one is already running. Check
`snapshotsTaken()` against `logCompactions()`: equal means compaction is working and the policy is
simply too generous; `logCompactions()` lagging behind is expected in two cases: a request is
dropped when the event queue is full, and one is skipped when an installed snapshot already compacted
further. Both heal on the next snapshot, which asks again. `logCompactions()` staying flat across many
snapshots is not expected and is a bug worth a report.

**"A node will not start."** Refusing to start is deliberate. The layout version is checked, every
record carries a CRC, and a snapshot that fails its checksum is skipped in favour of the one before
it. If nothing readable is left, the node has lost its disk; see the caveat under
[Restoring](#restoring) before starting it empty.

**"A crash left a `.snap.tmp` file."** It is deleted on the next start. A snapshot only ever appears
under its final name once all of its bytes are on disk.

**"A follower is stuck asking for entries that no longer exist."** That is the case the snapshot
transfer exists for, and it resolves itself within one snapshot timeout
(`RaftConfig.snapshotTimeoutTicks`, 40 ticks by default). If it does not, the snapshot is larger
than `TransportConfig.maxSnapshotBytes` on the receiving side, and the receiver refuses it rather
than buffering it.

## Configuration that matters for recovery

| Setting | Default | Why it matters |
|---|---|---|
| `TransportConfig.snapshotChunkBytes` | 1 MiB | must be at most half of `maxMessageBytes` |
| `TransportConfig.maxSnapshotBytes` | 256 MiB | a transfer larger than this is refused, not buffered |
| `TransportConfig.snapshotChunkDeadline` | 30 s | longer than the delivery deadline on purpose: a chunk holds no append slot |
| `RaftConfig.snapshotTimeoutTicks` | 40 | how long a leader waits before sending a snapshot again |
| `FsyncPolicy` | `BATCHED` | `NEVER` is for tests only; it survives a process crash, not a power cut |
