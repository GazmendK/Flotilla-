/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.sim.invariant;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.flotilla.core.Bytes;
import dev.flotilla.core.LogEntry;
import dev.flotilla.core.NodeId;
import dev.flotilla.core.RaftRole;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.stream.LongStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class InvariantTest {

    private static final NodeId N1 = NodeId.of("n1");
    private static final NodeId N2 = NodeId.of("n2");

    private record NodeState(
            boolean running,
            int restarts,
            long term,
            RaftRole role,
            long commitIndex,
            LogView log,
            List<LogEntry> applied,
            long restoredFromSnapshotAt,
            long appliedIndex,
            long appliedDigest) {

        static NodeState of(
                boolean running,
                int restarts,
                long term,
                RaftRole role,
                long commitIndex,
                List<LogEntry> log,
                List<LogEntry> applied) {
            return new NodeState(running, restarts, term, role, commitIndex, LogView.of(log), applied, 0, 0, 0);
        }

        NodeState restoredAt(long index, long digest) {
            return new NodeState(running, restarts, term, role, commitIndex, log, applied, index, index, digest);
        }

        NodeState appliedThrough(long index, long digest) {
            return new NodeState(
                    running, restarts, term, role, commitIndex, log, applied, restoredFromSnapshotAt, index, digest);
        }
    }

    private static final class FakeWorld implements WorldView {

        private final TreeMap<NodeId, NodeState> snapshots = new TreeMap<>();

        FakeWorld with(NodeId id, NodeState snapshot) {
            snapshots.put(id, snapshot);
            return this;
        }

        private NodeState get(NodeId id) {
            NodeState snapshot = snapshots.get(id);
            if (snapshot == null) {
                throw new IllegalArgumentException("Unknown node " + id);
            }
            return snapshot;
        }

        @Override
        public long time() {
            return 0;
        }

        @Override
        public SortedSet<NodeId> nodes() {
            return snapshots.navigableKeySet();
        }

        @Override
        public boolean isRunning(NodeId id) {
            return get(id).running();
        }

        @Override
        public int restarts(NodeId id) {
            return get(id).restarts();
        }

        @Override
        public long term(NodeId id) {
            return get(id).term();
        }

        @Override
        public RaftRole role(NodeId id) {
            return get(id).role();
        }

        @Override
        public long commitIndex(NodeId id) {
            return get(id).commitIndex();
        }

        @Override
        public LogView log(NodeId id) {
            return get(id).log();
        }

        @Override
        public long restoredFromSnapshotAt(NodeId id) {
            return get(id).restoredFromSnapshotAt();
        }

        @Override
        public long appliedIndex(NodeId id) {
            return get(id).appliedIndex();
        }

        @Override
        public long appliedDigest(NodeId id) {
            return get(id).appliedDigest();
        }

        @Override
        public List<LogEntry> appliedThisStep(NodeId id) {
            return get(id).applied();
        }
    }

    private static List<LogEntry> log(long... terms) {
        return LongStream.range(0, terms.length)
                .mapToObj(offset -> LogEntry.noOp(terms[(int) offset], offset + 1))
                .toList();
    }

    private static NodeState node(RaftRole role, long term, long commitIndex, List<LogEntry> log) {
        return NodeState.of(true, 0, term, role, commitIndex, log, List.of());
    }

    private static List<LogEntry> logFrom(long firstIndex, long... terms) {
        return LongStream.range(0, terms.length)
                .mapToObj(offset -> LogEntry.noOp(terms[(int) offset], firstIndex + offset))
                .toList();
    }

    @Test
    @DisplayName("Election Safety rejects two leaders in one term")
    void electionSafetyDetectsASplitBrain() {
        ElectionSafety invariant = new ElectionSafety();
        FakeWorld world = new FakeWorld()
                .with(N1, node(RaftRole.LEADER, 4, 0, log(4)))
                .with(N2, node(RaftRole.LEADER, 4, 0, log(4)));

        assertThatThrownBy(() -> invariant.observe(world))
                .isInstanceOf(InvariantViolation.class)
                .hasMessageContaining("was led by both");
    }

    @Test
    @DisplayName("Election Safety accepts leaders in different terms")
    void electionSafetyAcceptsSuccessiveLeaders() {
        ElectionSafety invariant = new ElectionSafety();
        invariant.observe(new FakeWorld().with(N1, node(RaftRole.LEADER, 4, 0, log(4))));

        assertThatCode(() -> invariant.observe(new FakeWorld().with(N2, node(RaftRole.LEADER, 5, 0, log(4, 5)))))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Leader Append-Only rejects a leader whose log shrinks")
    void leaderAppendOnlyDetectsATruncatingLeader() {
        LeaderAppendOnly invariant = new LeaderAppendOnly();
        invariant.observe(new FakeWorld().with(N1, node(RaftRole.LEADER, 3, 0, log(1, 2, 3))));

        assertThatThrownBy(() -> invariant.observe(new FakeWorld().with(N1, node(RaftRole.LEADER, 3, 0, log(1, 2)))))
                .isInstanceOf(InvariantViolation.class)
                .hasMessageContaining("log shrank");
    }

    @Test
    @DisplayName("Log Matching rejects equal index and term with different content")
    void logMatchingDetectsDivergentContent() {
        List<LogEntry> left = List.of(LogEntry.normal(1, 1, Bytes.ofUtf8("a")));
        List<LogEntry> right = List.of(LogEntry.normal(1, 1, Bytes.ofUtf8("b")));
        FakeWorld world = new FakeWorld()
                .with(N1, node(RaftRole.FOLLOWER, 1, 0, left))
                .with(N2, node(RaftRole.FOLLOWER, 1, 0, right));

        assertThatThrownBy(() -> new LogMatching().observe(world))
                .isInstanceOf(InvariantViolation.class)
                .hasMessageContaining("different entries at index 1");
    }

    @Test
    @DisplayName("Log Matching rejects logs that diverge and then agree again")
    void logMatchingDetectsAnImpossibleReconvergence() {
        FakeWorld world = new FakeWorld()
                .with(N1, node(RaftRole.FOLLOWER, 3, 0, log(1, 2, 3)))
                .with(N2, node(RaftRole.FOLLOWER, 3, 0, log(1, 9, 3)));

        assertThatThrownBy(() -> new LogMatching().observe(world))
                .isInstanceOf(InvariantViolation.class)
                .hasMessageContaining("diverge before index 3");
    }

    @Test
    @DisplayName("Log Matching accepts a plain divergent suffix")
    void logMatchingAcceptsADivergentSuffix() {
        FakeWorld world = new FakeWorld()
                .with(N1, node(RaftRole.FOLLOWER, 3, 0, log(1, 2, 3)))
                .with(N2, node(RaftRole.FOLLOWER, 3, 0, log(1, 2, 4, 4)));

        assertThatCode(() -> new LogMatching().observe(world)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Leader Completeness rejects a leader missing a committed entry")
    void leaderCompletenessDetectsALostCommit() {
        LeaderCompleteness invariant = new LeaderCompleteness();
        invariant.observe(new FakeWorld().with(N1, node(RaftRole.FOLLOWER, 2, 2, log(1, 2))));

        assertThatThrownBy(() -> invariant.observe(new FakeWorld().with(N2, node(RaftRole.LEADER, 3, 0, log(1)))))
                .isInstanceOf(InvariantViolation.class)
                .hasMessageContaining("missing committed index 2");
    }

    @Test
    @DisplayName("State Machine Safety rejects two nodes applying different entries at one index")
    void stateMachineSafetyDetectsDivergentApplication() {
        StateMachineSafety invariant = new StateMachineSafety();
        LogEntry first = LogEntry.normal(1, 1, Bytes.ofUtf8("a"));
        LogEntry second = LogEntry.normal(1, 1, Bytes.ofUtf8("b"));

        invariant.observe(new FakeWorld()
                .with(N1, NodeState.of(true, 0, 1, RaftRole.FOLLOWER, 1, List.of(first), List.of(first))));

        assertThatThrownBy(() -> invariant.observe(new FakeWorld()
                        .with(N2, NodeState.of(true, 0, 1, RaftRole.FOLLOWER, 1, List.of(second), List.of(second)))))
                .isInstanceOf(InvariantViolation.class)
                .hasMessageContaining("was applied as");
    }

    @Test
    @DisplayName("State Machine Safety rejects a gap in the applied sequence")
    void stateMachineSafetyDetectsASkippedEntry() {
        StateMachineSafety invariant = new StateMachineSafety();
        List<LogEntry> entries = log(1, 1, 1);

        invariant.observe(new FakeWorld()
                .with(N1, NodeState.of(true, 0, 1, RaftRole.FOLLOWER, 1, entries, List.of(entries.getFirst()))));

        assertThatThrownBy(() -> invariant.observe(new FakeWorld()
                        .with(N1, NodeState.of(true, 0, 1, RaftRole.FOLLOWER, 3, entries, List.of(entries.get(2))))))
                .isInstanceOf(InvariantViolation.class)
                .hasMessageContaining("exactly once and in order");
    }

    @Test
    @DisplayName("Monotonic Progress rejects a commit index moving backwards")
    void monotonicProgressDetectsARegression() {
        MonotonicProgress invariant = new MonotonicProgress();
        invariant.observe(new FakeWorld().with(N1, node(RaftRole.FOLLOWER, 2, 3, log(1, 2, 2))));

        assertThatThrownBy(() ->
                        invariant.observe(new FakeWorld().with(N1, node(RaftRole.FOLLOWER, 2, 1, log(1, 2, 2)))))
                .isInstanceOf(InvariantViolation.class)
                .hasMessageContaining("back to 1");
    }

    @Test
    @DisplayName("Monotonic Progress allows a restart to lower the commit index, since unsynced state is lost")
    void monotonicProgressToleratesARestart() {
        MonotonicProgress invariant = new MonotonicProgress();
        invariant.observe(new FakeWorld().with(N1, node(RaftRole.FOLLOWER, 2, 3, log(1, 2, 2))));

        FakeWorld afterRestart =
                new FakeWorld().with(N1, NodeState.of(true, 1, 2, RaftRole.FOLLOWER, 1, log(1, 2), List.of()));

        assertThatCode(() -> invariant.observe(afterRestart)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Log Matching compares logs by index, so a compacted log is still checked against a full one")
    void logMatchingComparesByIndexNotByPosition() {
        List<LogEntry> compacted =
                List.of(LogEntry.noOp(3, 5), LogEntry.normal(3, 6, Bytes.ofUtf8("a")), LogEntry.noOp(3, 7));
        List<LogEntry> full = List.of(
                LogEntry.noOp(1, 1),
                LogEntry.noOp(1, 2),
                LogEntry.noOp(1, 3),
                LogEntry.noOp(2, 4),
                LogEntry.noOp(3, 5),
                LogEntry.normal(3, 6, Bytes.ofUtf8("b")),
                LogEntry.noOp(3, 7));
        FakeWorld world = new FakeWorld()
                .with(N1, NodeState.of(true, 0, 3, RaftRole.FOLLOWER, 0, compacted, List.of()))
                .with(N2, NodeState.of(true, 0, 3, RaftRole.FOLLOWER, 0, full, List.of()));

        assertThatThrownBy(() -> new LogMatching().observe(world))
                .isInstanceOf(InvariantViolation.class)
                .hasMessageContaining("different entries at index 6");
    }

    @Test
    @DisplayName("Leader Append-Only accepts a leader that compacted its own prefix away")
    void leaderAppendOnlyAcceptsCompaction() {
        LeaderAppendOnly invariant = new LeaderAppendOnly();
        invariant.observe(new FakeWorld().with(N1, node(RaftRole.LEADER, 3, 5, log(1, 2, 3, 3, 3))));

        FakeWorld afterCompaction = new FakeWorld()
                .with(N1, NodeState.of(true, 0, 3, RaftRole.LEADER, 5, logFrom(3, 3, 3, 3, 3), List.of()));

        assertThatCode(() -> invariant.observe(afterCompaction)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Leader Completeness treats a committed entry inside the snapshot as held")
    void leaderCompletenessAcceptsACommittedEntryInsideASnapshot() {
        LeaderCompleteness invariant = new LeaderCompleteness();
        invariant.observe(new FakeWorld().with(N1, node(RaftRole.FOLLOWER, 2, 3, log(2, 2, 2))));

        FakeWorld laterLeader =
                new FakeWorld().with(N2, NodeState.of(true, 0, 3, RaftRole.LEADER, 3, logFrom(3, 2, 3), List.of()));

        assertThatCode(() -> invariant.observe(laterLeader)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Leader Completeness still rejects a leader that is genuinely missing a committed entry")
    void leaderCompletenessStillDetectsALostCommitAboveTheSnapshot() {
        LeaderCompleteness invariant = new LeaderCompleteness();
        invariant.observe(new FakeWorld().with(N1, node(RaftRole.FOLLOWER, 2, 4, log(2, 2, 2, 2))));

        FakeWorld laterLeader =
                new FakeWorld().with(N2, NodeState.of(true, 0, 3, RaftRole.LEADER, 2, logFrom(3, 2), List.of()));

        assertThatThrownBy(() -> invariant.observe(laterLeader))
                .isInstanceOf(InvariantViolation.class)
                .hasMessageContaining("missing committed index 4");
    }

    @Test
    @DisplayName("Monotonic Progress measures the commit index against the last index, not the entry count")
    void monotonicProgressAcceptsACommitIndexAboveTheEntryCount() {
        FakeWorld compacted = new FakeWorld()
                .with(N1, NodeState.of(true, 0, 2, RaftRole.FOLLOWER, 8, logFrom(6, 2, 2, 2, 2, 2), List.of()));

        assertThatCode(() -> new MonotonicProgress().observe(compacted)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("State Machine Safety lets an installed snapshot move the applied position forward")
    void stateMachineSafetyAcceptsAnInstalledSnapshot() {
        StateMachineSafety invariant = new StateMachineSafety();
        List<LogEntry> entries = log(1);
        invariant.observe(new FakeWorld()
                .with(N1, NodeState.of(true, 0, 1, RaftRole.FOLLOWER, 1, entries, List.of(entries.getFirst()))));

        LogEntry afterSnapshot = LogEntry.noOp(4, 10);
        FakeWorld installed = new FakeWorld()
                .with(
                        N1,
                        NodeState.of(true, 0, 4, RaftRole.FOLLOWER, 10, logFrom(10, 4), List.of(afterSnapshot))
                                .restoredAt(9, 777));

        assertThatCode(() -> invariant.observe(installed)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("State Machine Safety rejects two replicas holding different state at the same applied index")
    void stateMachineSafetyDetectsDivergentStateAtTheSameIndex() {
        FakeWorld world = new FakeWorld()
                .with(N1, node(RaftRole.FOLLOWER, 2, 5, log(1, 1, 2, 2, 2)).appliedThrough(5, 111))
                .with(N2, node(RaftRole.FOLLOWER, 2, 5, log(1, 1, 2, 2, 2)).appliedThrough(5, 222));

        assertThatThrownBy(() -> new StateMachineSafety().observe(world))
                .isInstanceOf(InvariantViolation.class)
                .hasMessageContaining("must hold the same state");
    }
}
