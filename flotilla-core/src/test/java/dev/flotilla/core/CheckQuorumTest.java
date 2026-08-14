/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CheckQuorumTest {

    @Test
    @DisplayName("a leader that stops reaching a majority steps down on its own")
    void anIsolatedLeaderStepsDown() {
        TestCluster cluster = TestCluster.of(3);
        cluster.tick(100);
        NodeId leader = cluster.singleLeader();

        cluster.isolate(leader);
        cluster.tick(100);

        assertThat(cluster.role(leader)).isNotEqualTo(RaftRole.LEADER);
    }

    @Test
    @DisplayName("without CheckQuorum it keeps answering as leader from inside the minority")
    void withoutCheckQuorumAnIsolatedLeaderKeepsLeading() {
        TestCluster cluster = TestCluster.of(3, builder -> builder.checkQuorum(false));
        cluster.tick(100);
        NodeId leader = cluster.singleLeader();

        cluster.isolate(leader);
        cluster.tick(200);

        assertThat(cluster.role(leader)).isEqualTo(RaftRole.LEADER);
        assertThat(cluster.leaders()).hasSize(2);
    }

    @Test
    @DisplayName("a leader that still reaches a majority is left alone")
    void aLeaderWithAMajorityIsUnaffected() {
        TestCluster cluster = TestCluster.of(5);
        cluster.tick(100);
        NodeId leader = cluster.singleLeader();
        long term = cluster.term(leader);

        cluster.isolate(cluster.followers().getFirst());
        cluster.tick(500);

        assertThat(cluster.role(leader)).isEqualTo(RaftRole.LEADER);
        assertThat(cluster.term(leader)).isEqualTo(term);
    }
}
