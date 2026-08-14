/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class Figure7Test {

    private static final NodeId LEADER = NodeId.of("n1");
    private static final NodeId FOLLOWER = NodeId.of("n2");
    private static final NodeId WITNESS = NodeId.of("n3");

    private static final long[] LEADER_LOG = {1, 1, 1, 4, 4, 5, 5, 6, 6, 6};

    static Stream<Arguments> figure7Followers() {
        return Stream.of(
                Arguments.of("a missing one entry", new long[] {1, 1, 1, 4, 4, 5, 5, 6, 6}),
                Arguments.of("b far behind", new long[] {1, 1, 1, 4}),
                Arguments.of("c one extra entry", new long[] {1, 1, 1, 4, 4, 5, 5, 6, 6, 6, 6}),
                Arguments.of("d extra entries from a later term", new long[] {1, 1, 1, 4, 4, 5, 5, 6, 6, 6, 7, 7}),
                Arguments.of("e missing and divergent", new long[] {1, 1, 1, 4, 4, 4, 4}),
                Arguments.of("f divergent from index four", new long[] {1, 1, 1, 2, 2, 2, 3, 3, 3, 3, 3}));
    }

    @ParameterizedTest(name = "Figure 7 follower ({0})")
    @MethodSource("figure7Followers")
    void theLeaderBringsEveryFollowerIntoAgreement(String label, long[] followerLog) {
        SortedMap<NodeId, List<LogEntry>> logs = new TreeMap<>();
        logs.put(LEADER, TestCluster.logWithTerms(LEADER_LOG));
        logs.put(FOLLOWER, TestCluster.logWithTerms(followerLog));
        logs.put(WITNESS, TestCluster.logWithTerms(LEADER_LOG));
        TestCluster cluster = TestCluster.withLogs(logs, 8);

        cluster.campaign(LEADER);
        cluster.tick(50);

        assertThat(cluster.node(LEADER).isLeader())
                .as("%s: n1 must win the election", label)
                .isTrue();
        assertThat(cluster.logTerms(FOLLOWER))
                .as("%s: the follower log must match the leader exactly", label)
                .isEqualTo(cluster.logTerms(LEADER));
    }

    @ParameterizedTest(name = "Figure 7 follower ({0}) keeps its committed prefix")
    @MethodSource("figure7Followers")
    void theCommittedPrefixIsNeverRewritten(String label, long[] followerLog) {
        SortedMap<NodeId, List<LogEntry>> logs = new TreeMap<>();
        logs.put(LEADER, TestCluster.logWithTerms(LEADER_LOG));
        logs.put(FOLLOWER, TestCluster.logWithTerms(followerLog));
        logs.put(WITNESS, TestCluster.logWithTerms(LEADER_LOG));
        TestCluster cluster = TestCluster.withLogs(logs, 8);

        cluster.campaign(LEADER);
        cluster.tick(50);

        assertThat(cluster.logTerms(FOLLOWER).subList(0, 3))
                .as("%s: the entries from term 1 are common to every log in Figure 7", label)
                .containsExactly(1L, 1L, 1L);
    }
}
