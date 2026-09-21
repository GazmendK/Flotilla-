/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CatchUpTest {

    private static final NodeId N1 = NodeId.of("n1");
    private static final NodeId N2 = NodeId.of("n2");
    private static final NodeId N3 = NodeId.of("n3");
    private static final NodeId LEARNER = NodeId.of("n4");
    private static final int ELECTION_TIMEOUT = RaftConfig.defaults(N1).electionTimeoutMinTicks();

    private static TestCluster trioWithSpare() {
        TestCluster cluster = TestCluster.withMembers(ClusterConfig.ofVoters(N1, N2, N3), List.of(N1, N2, N3, LEARNER));
        cluster.tick(100);
        return cluster;
    }

    private static CatchUpStatus status(TestCluster cluster, NodeId leader) {
        return cluster.node(leader).catchUpStatus(LEARNER).orElseThrow();
    }

    private static boolean promote(TestCluster cluster, NodeId leader) {
        boolean accepted = cluster.node(leader)
                .proposeConfChange(new ConfChange.Promote(LEARNER))
                .isAccepted();
        cluster.deliver();
        return accepted;
    }

    @Test
    @DisplayName("a learner that answers promptly completes a round and may be promoted")
    void aResponsiveLearnerIsCaughtUp() {
        TestCluster cluster = trioWithSpare();
        NodeId leader = cluster.singleLeader();
        cluster.node(leader).proposeConfChange(new ConfChange.AddLearner(LEARNER));
        cluster.deliver();
        cluster.tick(3);

        CatchUpStatus status = status(cluster, leader);

        assertThat(status.caughtUp()).isTrue();
        assertThat(status.completedRounds()).isPositive();
        assertThat(status.lastRoundTicks()).isLessThanOrEqualTo(ELECTION_TIMEOUT);
        assertThat(promote(cluster, leader)).isTrue();
    }

    @Test
    @DisplayName("a learner whose first round took longer than an election timeout needs another before promotion")
    void aSlowRoundIsNotEnough() {
        TestCluster cluster = trioWithSpare();
        NodeId leader = cluster.singleLeader();
        cluster.isolate(LEARNER);
        cluster.node(leader).proposeConfChange(new ConfChange.AddLearner(LEARNER));
        cluster.deliver();
        for (int i = 0; i < 20; i++) {
            cluster.propose(leader, "while the learner is away " + i);
        }
        cluster.tick(3 * ELECTION_TIMEOUT);

        cluster.heal();
        cluster.tick();

        CatchUpStatus afterOneRound = status(cluster, leader);
        assertThat(afterOneRound.matchIndex())
                .as("the learner has every entry")
                .isEqualTo(cluster.log(leader).lastIndex());
        assertThat(afterOneRound.lastRoundTicks()).isGreaterThan(ELECTION_TIMEOUT);
        assertThat(afterOneRound.caughtUp())
                .as("a round that took this long says the learner may not keep up once it votes")
                .isFalse();
        assertThat(promote(cluster, leader)).isFalse();

        cluster.tick(2);

        assertThat(status(cluster, leader).caughtUp()).isTrue();
        assertThat(promote(cluster, leader)).isTrue();
    }

    @Test
    @DisplayName("a learner that has every entry but has stopped answering is no longer caught up")
    void aSilentLearnerFallsBehind() {
        TestCluster cluster = trioWithSpare();
        NodeId leader = cluster.singleLeader();
        cluster.node(leader).proposeConfChange(new ConfChange.AddLearner(LEARNER));
        cluster.deliver();
        cluster.tick(3);
        assertThat(status(cluster, leader).caughtUp()).isTrue();

        cluster.isolate(LEARNER);
        cluster.tick(ELECTION_TIMEOUT + 1);

        CatchUpStatus status = status(cluster, leader);
        assertThat(status.matchIndex()).isEqualTo(cluster.log(leader).lastIndex());
        assertThat(status.currentRoundTicks()).isGreaterThan(ELECTION_TIMEOUT);
        assertThat(status.caughtUp()).isFalse();
        assertThat(cluster.node(leader).proposeConfChange(new ConfChange.Promote(LEARNER)))
                .isInstanceOfSatisfying(
                        ConfChangeResult.Rejected.class,
                        rejected -> assertThat(rejected.reason()).contains("has not caught up", "the current one"));
    }

    @Test
    @DisplayName("only the leader tracks catch-up, and only for learners")
    void catchUpIsTrackedForLearnersOnTheLeader() {
        TestCluster cluster = trioWithSpare();
        NodeId leader = cluster.singleLeader();
        cluster.node(leader).proposeConfChange(new ConfChange.AddLearner(LEARNER));
        cluster.deliver();
        NodeId follower = cluster.followers().stream()
                .filter(id -> !id.equals(LEARNER))
                .findFirst()
                .orElseThrow();

        assertThat(cluster.node(follower).catchUpStatus(LEARNER)).isEmpty();
        assertThat(cluster.node(leader).catchUpStatus(follower)).isEmpty();

        cluster.tick(3);
        promote(cluster, leader);
        assertThat(cluster.node(leader).catchUpStatus(LEARNER))
                .as("a voter is no longer catching up")
                .isEmpty();
    }
}
