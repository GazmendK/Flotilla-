/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.core;

import static org.assertj.core.api.Assertions.assertThat;

import dev.flotilla.core.message.RequestVoteRequest;
import dev.flotilla.core.port.RandomSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PreVoteTest {

    private static final NodeId N1 = NodeId.of("n1");
    private static final NodeId N2 = NodeId.of("n2");
    private static final NodeId N3 = NodeId.of("n3");
    private static final ClusterConfig CLUSTER = ClusterConfig.ofVoters(N1, N2, N3);

    private static RaftNode nodeAtTerm(long term) {
        return new RaftNode(
                RaftConfig.defaults(N1),
                CLUSTER,
                new InMemoryLogStore(),
                RandomSource.seeded(1),
                new HardState(term, null, 0));
    }

    @Test
    @DisplayName("a partitioned node never inflates its term while PreVote is on")
    void anIsolatedNodeKeepsItsTerm() {
        TestCluster cluster = TestCluster.of(3);
        cluster.tick(100);
        NodeId victim = cluster.followers().getFirst();
        long termBeforeIsolation = cluster.term(victim);

        cluster.isolate(victim);
        cluster.tick(500);

        assertThat(cluster.term(victim)).isEqualTo(termBeforeIsolation);
        assertThat(cluster.role(victim)).isEqualTo(RaftRole.PRE_CANDIDATE);
    }

    @Test
    @DisplayName("without PreVote the same node climbs to an arbitrarily high term")
    void anIsolatedNodeInflatesItsTermWithoutPreVote() {
        TestCluster cluster = TestCluster.of(3, builder -> builder.preVote(false));
        cluster.tick(100);
        NodeId victim = cluster.followers().getFirst();
        long termBeforeIsolation = cluster.term(victim);

        cluster.isolate(victim);
        cluster.tick(500);

        assertThat(cluster.term(victim)).isGreaterThan(termBeforeIsolation + 10);
        assertThat(cluster.role(victim)).isEqualTo(RaftRole.CANDIDATE);
    }

    @Test
    @DisplayName("answering a pre-vote never moves the receiver's term")
    void aPreVoteRequestLeavesTheReceiversTermAlone() {
        RaftNode node = nodeAtTerm(5);

        node.step(new RequestVoteRequest(N2, N1, 50, 0, 0, true));

        assertThat(node.currentTerm()).isEqualTo(5);
        assertThat(node.role()).isEqualTo(RaftRole.FOLLOWER);
    }

    @Test
    @DisplayName("the same request without the pre-vote flag does move it, which is what PreVote avoids")
    void aRealVoteRequestDoesMoveTheReceiversTerm() {
        RaftNode node = nodeAtTerm(5);

        node.step(new RequestVoteRequest(N2, N1, 50, 0, 0, false));

        assertThat(node.currentTerm()).isEqualTo(50);
    }

    @Test
    @DisplayName("a healthy cluster is undisturbed when the partitioned node returns")
    void aReturningNodeDoesNotUnseatTheLeader() {
        TestCluster cluster = TestCluster.of(3);
        cluster.tick(100);
        NodeId leader = cluster.singleLeader();
        NodeId victim = cluster.followers().getFirst();
        long termBefore = cluster.term(leader);

        cluster.isolate(victim);
        cluster.tick(500);
        cluster.heal();
        cluster.tick(100);

        assertThat(cluster.singleLeader()).isEqualTo(leader);
        assertThat(cluster.term(leader)).isEqualTo(termBefore);
        assertThat(cluster.role(victim)).isEqualTo(RaftRole.FOLLOWER);
    }

    @Test
    @DisplayName("a node in a pre-vote round is granted the real election only after a majority agrees")
    void aPreVoteGrantIsNotYetAnElection() {
        RaftNode node = nodeAtTerm(2);

        node.campaign();

        assertThat(node.role()).isEqualTo(RaftRole.PRE_CANDIDATE);
        assertThat(node.currentTerm()).isEqualTo(2);
        assertThat(node.votedFor()).isEmpty();
    }

    @Test
    @DisplayName("pre-vote requests advertise the term the candidate would use, not the one it has")
    void preVoteRequestsCarryTheProspectiveTerm() {
        RaftNode node = nodeAtTerm(7);

        node.campaign();

        assertThat(node.ready().messagesToSend()).allSatisfy(message -> {
            assertThat(message).isInstanceOf(RequestVoteRequest.class);
            assertThat(((RequestVoteRequest) message).preVote()).isTrue();
            assertThat(message.term()).isEqualTo(8);
        });
        assertThat(node.currentTerm()).isEqualTo(7);
    }
}
