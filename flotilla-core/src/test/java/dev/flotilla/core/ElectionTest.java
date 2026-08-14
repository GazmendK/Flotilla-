/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ElectionTest {

    @Test
    @DisplayName("a single-node cluster elects itself, because its own vote is already a majority")
    void singleNodeClusterElectsItself() {
        TestCluster cluster = TestCluster.of(1);

        cluster.tick(30);

        assertThat(cluster.leaders()).containsExactly(NodeId.of("n1"));
        assertThat(cluster.term(NodeId.of("n1"))).isEqualTo(1);
    }

    @ParameterizedTest(name = "{0} nodes elect exactly one leader")
    @ValueSource(ints = {3, 5, 7})
    void electsExactlyOneLeader(int size) {
        TestCluster cluster = TestCluster.of(size);

        cluster.tick(100);

        assertThat(cluster.leaders()).hasSize(1);
    }

    @Test
    @DisplayName("every follower learns who the leader is")
    void followersLearnTheLeader() {
        TestCluster cluster = TestCluster.of(5);
        cluster.tick(100);

        NodeId leader = cluster.singleLeader();

        assertThat(cluster.followers())
                .allSatisfy(
                        follower -> assertThat(cluster.node(follower).leader()).contains(leader));
    }

    @Test
    @DisplayName("all nodes agree on the term once an election has settled")
    void everyoneConvergesOnTheSameTerm() {
        TestCluster cluster = TestCluster.of(5);
        cluster.tick(100);

        long leaderTerm = cluster.term(cluster.singleLeader());

        assertThat(cluster.followers())
                .allSatisfy(follower -> assertThat(cluster.term(follower)).isEqualTo(leaderTerm));
    }

    @Test
    @DisplayName("an established leader is not replaced while it keeps reaching a majority")
    void anEstablishedLeaderStaysStable() {
        TestCluster cluster = TestCluster.of(5);
        cluster.tick(100);
        NodeId leader = cluster.singleLeader();
        long term = cluster.term(leader);

        cluster.tick(1000);

        assertThat(cluster.singleLeader()).isEqualTo(leader);
        assertThat(cluster.term(leader)).isEqualTo(term);
    }

    @Test
    @DisplayName("a follower that stops hearing from its leader starts an election")
    void anIsolatedFollowerCampaigns() {
        TestCluster cluster = TestCluster.of(3);
        cluster.tick(100);
        NodeId follower = cluster.followers().getFirst();

        cluster.isolate(follower);
        cluster.tick(60);

        assertThat(cluster.role(follower)).isNotEqualTo(RaftRole.FOLLOWER);
    }

    @Test
    @DisplayName("after the leader disappears the remaining majority elects a new one")
    void aNewLeaderIsElectedAfterTheOldOneDisappears() {
        TestCluster cluster = TestCluster.of(5);
        cluster.tick(100);
        NodeId oldLeader = cluster.singleLeader();

        cluster.isolate(oldLeader);
        cluster.tick(100);

        assertThat(cluster.leaders()).hasSize(1).doesNotContain(oldLeader);
    }
}
