/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.TreeSet;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ClusterConfigTest {
    private static ClusterConfig withVoters(int count) {
        return ClusterConfig.ofVoters(IntStream.rangeClosed(1, count)
                .mapToObj(i -> NodeId.of("n" + i))
                .toList());
    }

    @ParameterizedTest(name = "{0} voters -> quorum {1}, tolerates {2} failures")
    @CsvSource({"1,1,0", "2,2,0", "3,2,1", "4,3,1", "5,3,2", "6,4,2", "7,4,3"})
    @DisplayName("quorum is a strict majority, which is why even cluster sizes buy nothing")
    void quorumAndFaultTolerance(int voters, int expectedQuorum, int expectedTolerance) {
        ClusterConfig config = withVoters(voters);

        assertThat(config.quorum()).isEqualTo(expectedQuorum);
        assertThat(config.faultTolerance()).isEqualTo(expectedTolerance);
    }

    @Test
    @DisplayName("learners replicate but do not raise the quorum")
    void learnersDoNotCountTowardsQuorum() {
        ClusterConfig config = withVoters(3).withLearner(NodeId.of("n4")).withLearner(NodeId.of("n5"));

        assertThat(config.quorum()).isEqualTo(2);
        assertThat(config.allMembers()).hasSize(5);
        assertThat(config.isLearner(NodeId.of("n4"))).isTrue();
        assertThat(config.isVoter(NodeId.of("n4"))).isFalse();
    }

    @Test
    @DisplayName("promotion moves a node from learner to voter and only then changes the quorum")
    void promotionChangesTheQuorum() {
        ClusterConfig withLearner = withVoters(3).withLearner(NodeId.of("n4"));
        assertThat(withLearner.quorum()).isEqualTo(2);

        ClusterConfig promoted = withLearner.withPromotion(NodeId.of("n4"));

        assertThat(promoted.quorum()).isEqualTo(3);
        assertThat(promoted.isVoter(NodeId.of("n4"))).isTrue();
        assertThat(promoted.learners()).isEmpty();
    }

    @Test
    void removalDropsAMemberFromEitherSet() {
        ClusterConfig config = withVoters(3).withLearner(NodeId.of("n4"));

        assertThat(config.without(NodeId.of("n2")).voters()).hasSize(2);
        assertThat(config.without(NodeId.of("n4")).learners()).isEmpty();
    }

    @Test
    void rejectsAClusterWithoutVoters() {
        assertThatThrownBy(() -> new ClusterConfig(new TreeSet<>(), new TreeSet<>()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one voter");
    }

    @Test
    @DisplayName("a node cannot be a voter and a learner at once")
    void rejectsOverlappingRoles() {
        TreeSet<NodeId> voters = new TreeSet<>(List.of(NodeId.of("n1")));
        TreeSet<NodeId> learners = new TreeSet<>(List.of(NodeId.of("n1")));

        assertThatThrownBy(() -> new ClusterConfig(voters, learners))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be a voter and a learner");
    }

    @Test
    void rejectsPromotingSomeoneWhoIsNotALearner() {
        ClusterConfig config = withVoters(3);

        assertThatThrownBy(() -> config.withPromotion(NodeId.of("n1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a learner");
    }

    @Test
    @DisplayName("members iterate in sorted order, never in hash order")
    void membersIterateDeterministically() {
        ClusterConfig config = ClusterConfig.ofVoters(NodeId.of("n3"), NodeId.of("n1"), NodeId.of("n2"));

        assertThat(config.voters()).extracting(NodeId::value).containsExactly("n1", "n2", "n3");
    }

    @Test
    void memberSetsAreUnmodifiable() {
        ClusterConfig config = withVoters(3);

        assertThatThrownBy(() -> config.voters().add(NodeId.of("intruder")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("the constructor copies, so a later change to the caller's set does not leak in")
    void constructorCopiesItsInput() {
        TreeSet<NodeId> voters = new TreeSet<>(List.of(NodeId.of("n1")));
        ClusterConfig config = new ClusterConfig(voters, new TreeSet<>());

        voters.add(NodeId.of("n2"));

        assertThat(config.voters()).hasSize(1);
    }
}
