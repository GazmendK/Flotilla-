# Implementation notes: mapping the paper to the code

Every rule below is either implemented and pointed at a location, or marked with the phase that
will implement it. The point of the table is that "we implement Figure 2 completely" should be a
claim anyone can check in a few minutes rather than one they have to take on trust.

Types and methods that implement a named rule carry a `@RaftSpec` annotation with the section
reference, so the same mapping is reachable from the code side.

References are to [In Search of an Understandable Consensus Algorithm (Extended
Version)](https://raft.github.io/raft.pdf) unless marked *dissertation*, which means
[Consensus: Bridging Theory and Practice](https://github.com/ongardie/dissertation).

## Figure 2 — State

| Rule | Where | Status |
|---|---|---|
| `currentTerm`, `votedFor` persisted before responding | `HardState`, `Ready.requiresSync()` | modelled; the sync itself is Phase 7 |
| `log[]` persisted | `LogStore`, `InMemoryLogStore` | in memory; durable store is Phase 6 |
| `commitIndex` volatile | `RaftNode.commitIndex` | advanced in Phase 4 |
| `lastApplied` volatile | — | Phase 7, owned by the apply loop |
| `nextIndex[]`, `matchIndex[]` on leaders | — | Phase 4 |

## Figure 2 — RequestVote RPC

| Rule | Where | Status |
|---|---|---|
| 1. Reply false if `term < currentTerm` | `RaftNode.replyToStaleSender` | done |
| 2. Grant if `votedFor` is null or the candidate, **and** the candidate's log is at least as up to date | `RaftNode.handleRequestVote` | done |

## Figure 2 — AppendEntries RPC

| Rule | Where | Status |
|---|---|---|
| 1. Reply false if `term < currentTerm` | `RaftNode.replyToStaleSender` | done |
| 2. Reply false if the entry at `prevLogIndex` does not match `prevLogTerm` | `RaftNode.handleAppendEntries` | done |
| 3. Delete a conflicting entry and everything after it | `LogStore.truncateSuffixFrom` | Phase 4 |
| 4. Append any new entries | — | Phase 4 |
| 5. `commitIndex = min(leaderCommit, index of last new entry)` | — | Phase 4 |

## Figure 2 — Rules for Servers

| Rule | Where | Status |
|---|---|---|
| All servers: apply committed entries in order | — | Phase 7 |
| All servers: a higher term makes you a follower | `RaftNode.step` | done |
| Followers: respond to leaders and candidates | `RaftNode.step` | done |
| Followers: start an election on timeout | `RaftNode.tickElectionTimeout` | done |
| Candidates: increment term, vote for self, request votes | `RaftNode.startElection` | done |
| Candidates: become leader on a majority | `RaftNode.handleRequestVoteResponse` | done |
| Candidates: step down for a new leader | `RaftNode.handleAppendEntries` | done |
| Candidates: restart the election on timeout | `RaftNode.tickElectionTimeout` | done |
| Leaders: send periodic heartbeats | `RaftNode.tickLeader` | done |
| Leaders: replicate on proposal, retry on failure | — | Phase 4 |
| Leaders: advance `commitIndex` on a majority, current term only | — | Phase 4 |

## Figure 3 — Safety properties

Checked mechanically from Phase 5, when the simulation exists. Until then they hold by
construction of the election rules only.

| Property | Status |
|---|---|
| Election Safety | Phase 5 |
| Leader Append-Only | Phase 5 |
| Log Matching | Phase 5 |
| Leader Completeness | Phase 5 |
| State Machine Safety | Phase 5 |

## Extensions beyond the paper

| Extension | Where | Status |
|---|---|---|
| PreVote (*dissertation* §9.6) | `RaftNode.startElection`, `keepsTermOnHigherTerm` | done, on by default |
| CheckQuorum | `RaftNode.tickLeader` | done, on by default |
| Leader lease against disruptive votes | `RaftNode.isWithinLeaderLease` | done |
| Conflict hints for fast log backtracking | `AppendEntriesResponse` | fields exist, used in Phase 4 |
| Learners (*dissertation* §4.2.1) | `ClusterConfig` | modelled; promotion is Phase 12 |
| Leadership transfer (*dissertation* §3.10) | `TimeoutNowRequest` | message exists, handled in Phase 12 |
| ReadIndex (*dissertation* §6.4) | `ReadIndexRequest`, `ReadState` | messages exist, handled in Phase 11 |
| Snapshots (Figure 13) | `InstallSnapshotRequest` | message exists, handled in Phase 10 |

## Deliberate deviations

**Time is counted in ticks, not milliseconds.** Election and heartbeat timeouts are expressed as
tick counts and the core never reads a clock. See [ADR-0006](adr/0006-tick-based-logical-time.md).

**The leader's CheckQuorum interval uses the base election timeout, not the randomized one.**
Randomization exists to break split votes among candidates; a leader has no such contention, and
using the randomized value would make the leader lease intermittently lapse for no benefit.

**There is no separate heartbeat message.** A heartbeat is an `AppendEntries` with no entries,
exactly as in the paper, so the term and commit-index rules that make it work are not duplicated.
