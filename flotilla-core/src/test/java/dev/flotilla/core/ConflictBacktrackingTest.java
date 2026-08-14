/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.LongStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ConflictBacktrackingTest {

    private static final NodeId LEADER = NodeId.of("n1");
    private static final NodeId DIVERGENT = NodeId.of("n2");
    private static final NodeId WITNESS = NodeId.of("n3");

    private static final int DIVERGENT_ENTRIES = 1000;

    private static List<LogEntry> logOfTerm(long term, int count) {
        return LongStream.rangeClosed(1, count)
                .mapToObj(index -> LogEntry.noOp(term, index))
                .toList();
    }

    @Test
    @DisplayName("a follower that diverged by a thousand entries resynchronizes in a handful of round trips")
    void divergentSuffixIsSkippedInsteadOfWalkedBack() {
        SortedMap<NodeId, List<LogEntry>> logs = new TreeMap<>();
        logs.put(LEADER, logOfTerm(1, DIVERGENT_ENTRIES));
        logs.put(DIVERGENT, logOfTerm(2, DIVERGENT_ENTRIES));
        logs.put(WITNESS, logOfTerm(1, DIVERGENT_ENTRIES));
        TestCluster cluster = TestCluster.withLogs(logs, 5);

        cluster.campaign(LEADER);

        assertThat(cluster.logTerms(DIVERGENT)).isEqualTo(cluster.logTerms(LEADER));
        assertThat(cluster.appendRequestsDeliveredTo(DIVERGENT))
                .as("without conflict hints this would take one round trip per entry")
                .isLessThan(DIVERGENT_ENTRIES / 10);
    }

    @Test
    @DisplayName("a follower whose log is merely short is caught up without probing backwards at all")
    void aShortLogIsFilledForwards() {
        SortedMap<NodeId, List<LogEntry>> logs = new TreeMap<>();
        logs.put(LEADER, logOfTerm(1, DIVERGENT_ENTRIES));
        logs.put(DIVERGENT, logOfTerm(1, 5));
        logs.put(WITNESS, logOfTerm(1, DIVERGENT_ENTRIES));
        TestCluster cluster = TestCluster.withLogs(logs, 5);

        cluster.campaign(LEADER);

        assertThat(cluster.logTerms(DIVERGENT)).isEqualTo(cluster.logTerms(LEADER));
        assertThat(cluster.appendRequestsDeliveredTo(DIVERGENT)).isLessThan(DIVERGENT_ENTRIES / 10);
    }
}
