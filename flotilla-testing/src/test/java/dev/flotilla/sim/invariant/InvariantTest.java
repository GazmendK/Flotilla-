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

    private record Snapshot(
            boolean running,
            int restarts,
            long term,
            RaftRole role,
            long commitIndex,
            List<LogEntry> log,
            List<LogEntry> applied) {}

    private static final class FakeWorld implements WorldView {

        private final TreeMap<NodeId, Snapshot> snapshots = new TreeMap<>();

        FakeWorld with(NodeId id, Snapshot snapshot) {
            snapshots.put(id, snapshot);
            return this;
        }

        private Snapshot get(NodeId id) {
            Snapshot snapshot = snapshots.get(id);
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
        public List<LogEntry> log(NodeId id) {
            return get(id).log();
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

    private static Snapshot node(RaftRole role, long term, long commitIndex, List<LogEntry> log) {
        return new Snapshot(true, 0, term, role, commitIndex, log, List.of());
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
                .with(N1, new Snapshot(true, 0, 1, RaftRole.FOLLOWER, 1, List.of(first), List.of(first))));

        assertThatThrownBy(() -> invariant.observe(new FakeWorld()
                        .with(N2, new Snapshot(true, 0, 1, RaftRole.FOLLOWER, 1, List.of(second), List.of(second)))))
                .isInstanceOf(InvariantViolation.class)
                .hasMessageContaining("was applied as");
    }

    @Test
    @DisplayName("State Machine Safety rejects a gap in the applied sequence")
    void stateMachineSafetyDetectsASkippedEntry() {
        StateMachineSafety invariant = new StateMachineSafety();
        List<LogEntry> entries = log(1, 1, 1);

        invariant.observe(new FakeWorld()
                .with(N1, new Snapshot(true, 0, 1, RaftRole.FOLLOWER, 1, entries, List.of(entries.getFirst()))));

        assertThatThrownBy(() -> invariant.observe(new FakeWorld()
                        .with(N1, new Snapshot(true, 0, 1, RaftRole.FOLLOWER, 3, entries, List.of(entries.get(2))))))
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
                new FakeWorld().with(N1, new Snapshot(true, 1, 2, RaftRole.FOLLOWER, 1, log(1, 2), List.of()));

        assertThatCode(() -> invariant.observe(afterRestart)).doesNotThrowAnyException();
    }
}
