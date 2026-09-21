/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.flotilla.core.message.AppendEntriesRequest;
import dev.flotilla.core.message.AppendEntriesResponse;
import dev.flotilla.core.message.RequestVoteResponse;
import dev.flotilla.core.port.RandomSource;
import dev.flotilla.core.port.SnapshotStore;
import java.util.List;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MembershipTest {

    private static final NodeId N1 = NodeId.of("n1");
    private static final NodeId N2 = NodeId.of("n2");
    private static final NodeId N3 = NodeId.of("n3");
    private static final NodeId N4 = NodeId.of("n4");
    private static final NodeId N5 = NodeId.of("n5");
    private static final ClusterConfig TRIO = ClusterConfig.ofVoters(N1, N2, N3);
    private static final ClusterConfig TRIO_AND_LEARNER =
            new ClusterConfig(new TreeSet<>(List.of(N1, N2, N3)), new TreeSet<>(List.of(N4)));

    private static TestCluster trioWithSpares() {
        TestCluster cluster = TestCluster.withMembers(TRIO, List.of(N1, N2, N3, N4, N5));
        cluster.tick(100);
        return cluster;
    }

    private static ConfChangeResult change(TestCluster cluster, NodeId leader, ConfChange change) {
        ConfChangeResult result = cluster.node(leader).proposeConfChange(change);
        cluster.deliver();
        return result;
    }

    private static String reason(ConfChangeResult result) {
        assertThat(result).isInstanceOf(ConfChangeResult.Rejected.class);
        return ((ConfChangeResult.Rejected) result).reason();
    }

    @Test
    @DisplayName("a learner receives every entry but is never part of a majority")
    void aLearnerIsReplicatedToButNotCounted() {
        TestCluster cluster = trioWithSpares();
        NodeId leader = cluster.singleLeader();

        assertThat(change(cluster, leader, new ConfChange.AddLearner(N4)).isAccepted())
                .isTrue();
        cluster.propose(leader, "for everyone");
        assertThat(cluster.log(N4).lastIndex()).isEqualTo(cluster.log(leader).lastIndex());

        cluster.isolate(N4);
        long before = cluster.commitIndex(leader);
        cluster.propose(leader, "without the learner");
        assertThat(cluster.commitIndex(leader))
                .as("the three voters commit on their own; the learner is not waited for")
                .isGreaterThan(before);
        assertThat(cluster.node(N4).role()).isNotIn(RaftRole.CANDIDATE, RaftRole.PRE_CANDIDATE);
    }

    @Test
    @DisplayName("promoting a caught-up learner makes it a voter and raises the quorum")
    void promotionChangesTheQuorum() {
        TestCluster cluster = trioWithSpares();
        NodeId leader = cluster.singleLeader();
        change(cluster, leader, new ConfChange.AddLearner(N4));
        cluster.propose(leader, "catch up");

        ConfChangeResult promoted = change(cluster, leader, new ConfChange.Promote(N4));

        assertThat(promoted.isAccepted()).isTrue();
        assertThat(cluster.node(leader).configuration().voters()).containsExactly(N1, N2, N3, N4);
        assertThat(cluster.node(leader).configuration().quorum()).isEqualTo(3);

        List<NodeId> others = cluster.followers().stream()
                .filter(id -> !id.equals(N5))
                .limit(2)
                .toList();
        others.forEach(cluster::isolate);
        long before = cluster.commitIndex(leader);
        cluster.propose(leader, "needs three of four");
        assertThat(cluster.commitIndex(leader))
                .as("with four voters, the leader and one follower are no longer a majority")
                .isEqualTo(before);
    }

    @Test
    @DisplayName("a configuration takes effect the moment it is appended, before it commits")
    void aConfigurationTakesEffectOnAppend() {
        TestCluster cluster = trioWithSpares();
        NodeId leader = cluster.singleLeader();
        cluster.followers().forEach(cluster::isolate);

        ConfChangeResult result = cluster.node(leader).proposeConfChange(new ConfChange.AddLearner(N4));

        assertThat(result.isAccepted()).isTrue();
        assertThat(cluster.node(leader).configuration().learners()).containsExactly(N4);
        assertThat(cluster.node(leader).configurationIndex())
                .as("appended, not committed, and already in force")
                .isGreaterThan(cluster.commitIndex(leader));
    }

    @Test
    @DisplayName("a follower drops a configuration again when the entry that carried it is overwritten")
    void anOverwrittenConfigurationIsForgotten() {
        InMemoryLogStore log = new InMemoryLogStore();
        RaftNode follower = new RaftNode(
                RaftConfig.defaults(N1), TRIO, log, SnapshotStore.none(), RandomSource.seeded(3), HardState.INITIAL);
        LogEntry proposal = LogEntry.configuration(1, 1, ClusterConfigCodec.encode(TRIO_AND_LEARNER));

        follower.step(new AppendEntriesRequest(N2, N1, 1, 0, 0, List.of(proposal), 0, 0));
        assertThat(follower.configuration()).isEqualTo(TRIO_AND_LEARNER);

        follower.step(new AppendEntriesRequest(N3, N1, 2, 0, 0, List.of(LogEntry.noOp(2, 1)), 0, 0));

        assertThat(follower.configuration())
                .as("the configuration came from an entry that is no longer in the log")
                .isEqualTo(TRIO);
    }

    @Test
    @DisplayName("a second change is refused while the first has not committed")
    void onlyOneChangeAtATime() {
        TestCluster cluster = trioWithSpares();
        NodeId leader = cluster.singleLeader();
        cluster.followers().forEach(cluster::isolate);
        assertThat(cluster.node(leader)
                        .proposeConfChange(new ConfChange.AddLearner(N4))
                        .isAccepted())
                .isTrue();

        ConfChangeResult second = cluster.node(leader).proposeConfChange(new ConfChange.AddLearner(N5));

        assertThat(reason(second)).contains("only one change may be in flight");
    }

    @Test
    @DisplayName("a new leader changes nothing before it has committed an entry of its own term")
    void noChangeBeforeTheNoOpCommits() {
        RaftNode node = new RaftNode(
                RaftConfig.defaults(N1),
                TRIO,
                new InMemoryLogStore(),
                SnapshotStore.none(),
                RandomSource.seeded(5),
                HardState.INITIAL);
        node.campaign();
        node.step(new RequestVoteResponse(N2, N1, 1, true, true));
        node.step(new RequestVoteResponse(N2, N1, 1, true, false));
        assertThat(node.isLeader()).isTrue();

        assertThat(reason(node.proposeConfChange(new ConfChange.AddLearner(N4))))
                .contains("not committed an entry of its own term");

        node.step(AppendEntriesResponse.accepted(N2, N1, 1, 1, 0));
        assertThat(node.proposeConfChange(new ConfChange.AddLearner(N4)).isAccepted())
                .isTrue();
    }

    @Test
    @DisplayName("a leader that removes itself keeps leading until the removal commits, then steps down")
    void aLeaderCanRemoveItself() {
        TestCluster cluster = trioWithSpares();
        NodeId leader = cluster.singleLeader();

        assertThat(change(cluster, leader, new ConfChange.Remove(leader)).isAccepted())
                .isTrue();

        assertThat(cluster.node(leader).isLeader()).isFalse();
        cluster.tick(100);
        List<NodeId> leaders = cluster.leaders();
        assertThat(leaders).hasSize(1).doesNotContain(leader);
        assertThat(cluster.propose(leaders.getFirst(), "after the handover")).isTrue();
        assertThat(cluster.node(leader).role())
                .as("a node outside the configuration never campaigns")
                .isEqualTo(RaftRole.FOLLOWER);
    }

    @Test
    @DisplayName("a removal that would leave no reachable majority is refused, with the arithmetic in the message")
    void aRemovalThatBreaksTheQuorumIsRefused() {
        TestCluster cluster = trioWithSpares();
        NodeId leader = cluster.singleLeader();
        NodeId unreachable = cluster.followers().stream()
                .filter(id -> !id.equals(N4) && !id.equals(N5))
                .findFirst()
                .orElseThrow();
        NodeId healthy = TRIO.voters().stream()
                .filter(id -> !id.equals(leader) && !id.equals(unreachable))
                .findFirst()
                .orElseThrow();
        cluster.isolate(unreachable);
        cluster.tick(30);

        assertThat(reason(cluster.node(leader).proposeConfChange(new ConfChange.Remove(healthy))))
                .contains("only 1 of them have been heard from recently");
        assertThat(change(cluster, leader, new ConfChange.Remove(unreachable)).isAccepted())
                .as("removing the node that is actually gone is exactly what an operator should do")
                .isTrue();
    }

    @Test
    void theLastVoterCannotBeRemoved() {
        TestCluster cluster = TestCluster.of(1);
        cluster.tick(50);
        NodeId only = cluster.singleLeader();

        assertThat(reason(cluster.node(only).proposeConfChange(new ConfChange.Remove(only))))
                .contains("at least one voter");
    }

    @Test
    @DisplayName("a learner that has not caught up is not promoted")
    void aLaggingLearnerIsNotPromoted() {
        TestCluster cluster = trioWithSpares();
        NodeId leader = cluster.singleLeader();
        change(cluster, leader, new ConfChange.AddLearner(N4));
        cluster.isolate(N4);
        cluster.propose(leader, "the learner misses this");

        assertThat(reason(cluster.node(leader).proposeConfChange(new ConfChange.Promote(N4))))
                .contains("has not caught up");
    }

    @Test
    @DisplayName("CheckQuorum counts voters only: a leader that hears from nothing but a learner steps down")
    void aLearnerCannotKeepALeaderInPower() {
        TestCluster cluster = trioWithSpares();
        NodeId leader = cluster.singleLeader();
        change(cluster, leader, new ConfChange.AddLearner(N4));
        TRIO.voters().stream().filter(id -> !id.equals(leader)).forEach(cluster::isolate);

        cluster.tick(40);

        assertThat(cluster.node(leader).isLeader()).isFalse();
    }

    @Test
    @DisplayName("votes from nodes outside the configuration do not count")
    void votesFromNonVotersAreIgnored() {
        RaftNode candidate = new RaftNode(
                RaftConfig.defaults(N1),
                TRIO_AND_LEARNER,
                new InMemoryLogStore(),
                SnapshotStore.none(),
                RandomSource.seeded(9),
                HardState.INITIAL);
        candidate.campaign();

        candidate.step(new RequestVoteResponse(N4, N1, 1, true, true));
        assertThat(candidate.role()).isEqualTo(RaftRole.PRE_CANDIDATE);

        candidate.step(new RequestVoteResponse(N2, N1, 1, true, true));
        assertThat(candidate.role()).isEqualTo(RaftRole.CANDIDATE);
    }

    @Test
    @DisplayName("the configuration survives compaction and a restart, carried by the snapshot")
    void theConfigurationSurvivesCompaction() {
        TestCluster cluster = trioWithSpares();
        NodeId leader = cluster.singleLeader();
        change(cluster, leader, new ConfChange.AddLearner(N4));
        cluster.propose(leader, "after the change");
        Snapshot snapshot = cluster.takeSnapshot(leader, cluster.commitIndex(leader));

        RaftNode restarted = new RaftNode(
                RaftConfig.defaults(leader),
                TRIO,
                cluster.log(leader),
                cluster.snapshots(leader),
                RandomSource.seeded(1),
                HardState.INITIAL);

        assertThat(snapshot.cluster().learners()).containsExactly(N4);
        assertThat(restarted.configuration())
                .as("the entry that added the learner is gone from the log; only the snapshot remembers it")
                .isEqualTo(snapshot.cluster());
    }

    @Test
    @DisplayName("a second snapshot still carries a configuration whose entry the first one compacted away")
    void aLaterSnapshotRemembersAnEarlierCompactedConfiguration() {
        TestCluster cluster = trioWithSpares();
        NodeId leader = cluster.singleLeader();
        change(cluster, leader, new ConfChange.AddLearner(N4));
        cluster.propose(leader, "first");
        cluster.takeSnapshot(leader, cluster.commitIndex(leader));
        cluster.propose(leader, "second");

        Snapshot later = cluster.takeSnapshot(leader, cluster.commitIndex(leader));

        assertThat(later.cluster().learners())
                .as("no configuration entry is left in the log; the base has to remember it")
                .containsExactly(N4);
    }

    @Test
    @DisplayName("a follower caught up by a snapshot takes the configuration from it")
    void anInstalledSnapshotCarriesTheConfiguration() {
        TestCluster cluster = trioWithSpares();
        NodeId leader = cluster.singleLeader();
        NodeId lagging = cluster.followers().stream()
                .filter(id -> TRIO.isVoter(id))
                .findFirst()
                .orElseThrow();
        cluster.isolate(lagging);
        change(cluster, leader, new ConfChange.AddLearner(N4));
        cluster.propose(leader, "more");
        cluster.takeSnapshot(leader, cluster.commitIndex(leader));

        cluster.heal();
        cluster.tick(20);

        assertThat(cluster.installedSnapshotsOf(lagging)).isNotEmpty();
        assertThat(cluster.node(lagging).configuration())
                .isEqualTo(cluster.node(leader).configuration());
    }

    @Test
    void aConfigurationRoundTripsThroughItsEncoding() {
        assertThat(ClusterConfigCodec.decode(ClusterConfigCodec.encode(TRIO_AND_LEARNER)))
                .isEqualTo(TRIO_AND_LEARNER);
        assertThatThrownBy(() -> ClusterConfigCodec.decode(Bytes.ofUtf8("nonsense")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
