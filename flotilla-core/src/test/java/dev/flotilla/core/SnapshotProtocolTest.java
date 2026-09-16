/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.flotilla.core.message.AppendEntriesRequest;
import dev.flotilla.core.message.InstallSnapshotRequest;
import dev.flotilla.core.message.InstallSnapshotResponse;
import dev.flotilla.core.message.RaftMessage;
import dev.flotilla.core.port.RandomSource;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.LongStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SnapshotProtocolTest {

    private static final NodeId N1 = NodeId.of("n1");
    private static final NodeId N2 = NodeId.of("n2");
    private static final ClusterConfig PAIR = ClusterConfig.ofVoters(N1, N2);
    private static final ClusterConfig SOLO_WITH_LEARNER =
            new ClusterConfig(new TreeSet<>(List.of(N1)), new TreeSet<>(List.of(N2)));

    private final InMemoryLogStore log = new InMemoryLogStore();
    private final InMemorySnapshotStore snapshots = new InMemorySnapshotStore();

    private RaftNode node(ClusterConfig cluster, HardState persisted) {
        return new RaftNode(RaftConfig.defaults(N1), cluster, log, snapshots, RandomSource.seeded(4242), persisted);
    }

    private void fillLog(long term, long fromIndex, int count) {
        log.append(LongStream.range(0, count)
                .mapToObj(offset -> LogEntry.normal(term, fromIndex + offset, Bytes.ofUtf8("v" + offset)))
                .toList());
    }

    private static Snapshot snapshotAt(long index, long term) {
        return new Snapshot(index, term, PAIR, Bytes.ofUtf8("state through " + index));
    }

    private static List<RaftMessage> drain(RaftNode node) {
        List<RaftMessage> messages = List.copyOf(node.ready().messagesToSend());
        node.advance();
        return messages;
    }

    @Test
    @DisplayName("a follower that fell behind a compacted prefix is caught up from a snapshot")
    void aFollowerBehindTheCompactedPrefixIsCaughtUpFromASnapshot() {
        TestCluster cluster = TestCluster.of(3);
        cluster.tick(100);
        NodeId leader = cluster.singleLeader();
        NodeId lagging = cluster.followers().getFirst();

        cluster.isolate(lagging);
        for (int i = 0; i < 20; i++) {
            cluster.propose(leader, "v" + i);
        }
        long snapshotIndex = cluster.commitIndex(leader);
        cluster.takeSnapshot(leader, snapshotIndex);
        cluster.propose(leader, "after the snapshot");

        cluster.heal();
        cluster.tick(50);

        assertThat(cluster.installedSnapshotsOf(lagging))
                .as("the leader can no longer name an entry the follower is missing, so it has to send state")
                .singleElement()
                .extracting(Snapshot::lastIncludedIndex)
                .isEqualTo(snapshotIndex);
        assertThat(cluster.log(lagging).firstIndex()).isEqualTo(snapshotIndex + 1);
        assertThat(cluster.logTerms(lagging)).isEqualTo(cluster.logTerms(leader));
        assertThat(cluster.commitIndex(lagging)).isEqualTo(cluster.commitIndex(leader));
    }

    @Test
    @DisplayName("the entries that follow a snapshot are accepted, so lastIncludedTerm has to survive compaction")
    void theFirstAppendAfterASnapshotIsAccepted() {
        TestCluster cluster = TestCluster.of(3);
        cluster.tick(100);
        NodeId leader = cluster.singleLeader();
        NodeId lagging = cluster.followers().getFirst();

        cluster.isolate(lagging);
        for (int i = 0; i < 10; i++) {
            cluster.propose(leader, "v" + i);
        }
        cluster.takeSnapshot(leader, cluster.commitIndex(leader));
        cluster.heal();
        cluster.tick(20);

        cluster.propose(leader, "one more");
        cluster.tick(20);

        assertThat(cluster.log(lagging).lastIndex())
                .isEqualTo(cluster.log(leader).lastIndex());
        assertThat(cluster.commitIndex(lagging)).isEqualTo(cluster.commitIndex(leader));
    }

    @Test
    @DisplayName("a heartbeat to a peer whose match index is below the compacted prefix does not blow up the leader")
    void heartbeatsSurviveCompaction() {
        TestCluster cluster = TestCluster.of(3);
        cluster.tick(100);
        NodeId leader = cluster.singleLeader();
        NodeId lagging = cluster.followers().getFirst();

        cluster.isolate(lagging);
        for (int i = 0; i < 10; i++) {
            cluster.propose(leader, "v" + i);
        }
        cluster.takeSnapshot(leader, cluster.commitIndex(leader));

        assertThatCode(() -> cluster.tick(5)).doesNotThrowAnyException();
        assertThat(cluster.leaders()).containsExactly(leader);
    }

    @Test
    @DisplayName("a snapshot older than what the follower already committed is refused, never applied")
    void aStaleSnapshotIsRefused() {
        fillLog(3, 1, 10);
        RaftNode follower = node(PAIR, new HardState(3, null, 10));

        follower.step(new InstallSnapshotRequest(N2, N1, 3, snapshotAt(4, 2)));

        Ready ready = follower.ready();
        assertThat(ready.snapshot()).isEmpty();
        assertThat(log.firstIndex()).isEqualTo(1);
        assertThat(log.lastIndex()).isEqualTo(10);
        assertThat(ready.messagesToSend()).singleElement().isEqualTo(new InstallSnapshotResponse(N1, N2, 3, 10, false));
    }

    @Test
    @DisplayName(
            "a follower whose log already reaches the snapshot point keeps its entries instead of throwing them away")
    void aSnapshotThatOnlyCoversAPrefixLeavesTheLogAlone() {
        fillLog(3, 1, 10);
        RaftNode follower = node(PAIR, new HardState(3, null, 0));

        follower.step(new InstallSnapshotRequest(N2, N1, 3, snapshotAt(5, 3)));

        Ready ready = follower.ready();
        assertThat(ready.snapshot()).isEmpty();
        assertThat(log.lastIndex()).isEqualTo(10);
        assertThat(follower.commitIndex()).isEqualTo(5);
        assertThat(ready.messagesToSend()).singleElement().isEqualTo(new InstallSnapshotResponse(N1, N2, 3, 5, false));
    }

    @Test
    @DisplayName("a snapshot that does not match the follower log replaces it, and the cluster config travels with it")
    void aSnapshotThatConflictsReplacesTheLog() {
        fillLog(1, 1, 4);
        RaftNode follower = node(PAIR, new HardState(1, null, 0));
        follower.step(new AppendEntriesRequest(
                N2, N1, 1, 4, 1, List.of(LogEntry.normal(1, 5, Bytes.ofUtf8("not yet on disk"))), 0, 0));
        Snapshot snapshot = snapshotAt(9, 5);

        follower.step(new InstallSnapshotRequest(N2, N1, 5, snapshot));

        Ready ready = follower.ready();
        assertThat(ready.snapshot()).contains(snapshot);
        assertThat(ready.snapshot().orElseThrow().cluster()).isEqualTo(PAIR);
        assertThat(ready.requiresSync())
                .as("the snapshot has to reach the disk before the response promises it is installed")
                .isTrue();
        assertThat(ready.committedEntriesToApply()).isEmpty();
        assertThat(ready.entriesToPersist())
                .as("entries the reset just discarded must not be written back behind the snapshot")
                .isEmpty();
        assertThat(log.firstIndex()).isEqualTo(10);
        assertThat(log.lastIndex()).isEqualTo(9);
        assertThat(log.termAt(9)).isEqualTo(5);
        assertThat(follower.commitIndex()).isEqualTo(9);
        assertThat(ready.messagesToSend()).contains(new InstallSnapshotResponse(N1, N2, 5, 9, true));
    }

    @Test
    @DisplayName("an append below the compacted prefix is answered with the snapshot point instead of a rejection")
    void anAppendBelowTheCompactedPrefixIsAnswered() {
        fillLog(3, 1, 10);
        RaftNode follower = node(PAIR, new HardState(3, null, 10));
        log.compactTo(8);

        follower.step(new AppendEntriesRequest(N2, N1, 3, 2, 3, List.of(), 10, 7));

        assertThat(drain(follower))
                .singleElement()
                .isEqualTo(dev.flotilla.core.message.AppendEntriesResponse.accepted(N1, N2, 3, 8, 7));
    }

    @Test
    @DisplayName("a node that restarts on a compacted log starts committed through its snapshot point")
    void aRestartOnACompactedLogDoesNotRereadTheCompactedPrefix() {
        fillLog(2, 1, 10);
        log.compactTo(6);

        RaftNode restarted = node(PAIR, new HardState(2, null, 0));

        assertThat(restarted.commitIndex()).isEqualTo(6);
        assertThatCode(restarted::ready).doesNotThrowAnyException();
        assertThat(restarted.ready().committedEntriesToApply()).isEmpty();
    }

    @Test
    @DisplayName("compaction is refused unless a snapshot already covers the entries that would go away")
    void compactionWithoutASnapshotIsRefused() {
        fillLog(1, 1, 10);
        RaftNode leader = node(PAIR, new HardState(1, null, 10));

        assertThatThrownBy(() -> leader.compactLog(5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("the newest snapshot only covers index 0");

        snapshots.save(snapshotAt(3, 1));
        assertThatThrownBy(() -> leader.compactLog(5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("only covers index 3");

        snapshots.save(snapshotAt(5, 1));
        leader.compactLog(5);
        assertThat(log.firstIndex()).isEqualTo(6);
    }

    @Test
    @DisplayName("compaction is refused beyond what the state machine has been handed")
    void compactionAheadOfTheStateMachineIsRefused() {
        fillLog(1, 1, 10);
        RaftNode follower = node(PAIR, new HardState(1, null, 4));
        snapshots.save(snapshotAt(9, 1));

        assertThatThrownBy(() -> follower.compactLog(9))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("only entries up to index 4");
    }

    @Test
    @DisplayName("a snapshot nobody answers is sent again once the leader stops waiting")
    void anUnansweredSnapshotIsSentAgain() {
        RaftNode leader = node(SOLO_WITH_LEARNER, HardState.INITIAL);
        leader.campaign();
        for (int i = 0; i < 5; i++) {
            leader.propose(Bytes.ofUtf8("v" + i));
        }
        drain(leader);

        snapshots.save(snapshotAt(leader.commitIndex(), leader.currentTerm()));
        leader.compactLog(leader.commitIndex());
        leader.propose(Bytes.ofUtf8("after"));

        assertThat(drain(leader))
                .filteredOn(InstallSnapshotRequest.class::isInstance)
                .hasSize(1);

        int timeout = RaftConfig.defaults(N1).snapshotTimeoutTicks();
        for (int tick = 0; tick < timeout - 1; tick++) {
            leader.tick();
        }
        assertThat(drain(leader))
                .as("resending before the timeout would waste the bandwidth the transfer still needs")
                .filteredOn(InstallSnapshotRequest.class::isInstance)
                .isEmpty();

        leader.tick();

        assertThat(drain(leader))
                .filteredOn(InstallSnapshotRequest.class::isInstance)
                .hasSize(1);
    }

    @Test
    @DisplayName("a peer that is already receiving a snapshot is not sent a second one on top of it")
    void aPeerWaitingForASnapshotIsNotSentAnother() {
        RaftNode leader = node(SOLO_WITH_LEARNER, HardState.INITIAL);
        leader.campaign();
        for (int i = 0; i < 5; i++) {
            leader.propose(Bytes.ofUtf8("v" + i));
        }
        drain(leader);
        snapshots.save(snapshotAt(leader.commitIndex(), leader.currentTerm()));
        leader.compactLog(leader.commitIndex());
        leader.propose(Bytes.ofUtf8("triggers the snapshot"));
        drain(leader);

        leader.propose(Bytes.ofUtf8("while the snapshot is in flight"));

        assertThat(drain(leader))
                .as("resending the whole state on every proposal would drown the follower it is meant to rescue")
                .noneMatch(InstallSnapshotRequest.class::isInstance)
                .noneMatch(AppendEntriesRequest.class::isInstance);
    }
}
