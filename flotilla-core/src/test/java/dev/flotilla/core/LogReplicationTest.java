/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LogReplicationTest {

    @Test
    @DisplayName("a new leader appends an entry of its own term before anything else")
    void aNewLeaderAppendsANoOp() {
        TestCluster cluster = TestCluster.of(3);
        cluster.tick(100);
        NodeId leader = cluster.singleLeader();

        assertThat(cluster.log(leader).entryAt(1)).map(LogEntry::type).contains(EntryType.NOOP);
        assertThat(cluster.log(leader).entryAt(1)).map(LogEntry::term).contains(cluster.term(leader));
    }

    @Test
    void aProposalIsReplicatedToEveryNode() {
        TestCluster cluster = TestCluster.of(3);
        cluster.tick(100);
        NodeId leader = cluster.singleLeader();

        assertThat(cluster.propose(leader, "hello")).isTrue();
        cluster.tick(20);

        for (NodeId node : List.of(NodeId.of("n1"), NodeId.of("n2"), NodeId.of("n3"))) {
            assertThat(cluster.logTerms(node)).isEqualTo(cluster.logTerms(leader));
        }
    }

    @Test
    @DisplayName("a proposal is committed and applied exactly once on every node")
    void aProposalIsAppliedExactlyOnce() {
        TestCluster cluster = TestCluster.of(3);
        cluster.tick(100);
        NodeId leader = cluster.singleLeader();

        cluster.propose(leader, "a");
        cluster.propose(leader, "b");
        cluster.propose(leader, "c");
        cluster.tick(50);

        for (NodeId node : List.of(NodeId.of("n1"), NodeId.of("n2"), NodeId.of("n3"))) {
            List<LogEntry> applied = cluster.appliedOf(node);
            assertThat(applied)
                    .extracting(LogEntry::index)
                    .doesNotHaveDuplicates()
                    .isSorted();
            assertThat(applied)
                    .filteredOn(entry -> entry.type() == EntryType.NORMAL)
                    .extracting(entry -> entry.data().toUtf8())
                    .containsExactly("a", "b", "c");
        }
    }

    @Test
    @DisplayName("the commit index advances on every node, not only on the leader")
    void everyNodeLearnsTheCommitIndex() {
        TestCluster cluster = TestCluster.of(3);
        cluster.tick(100);
        NodeId leader = cluster.singleLeader();

        cluster.propose(leader, "value");
        cluster.tick(50);

        long expected = cluster.commitIndex(leader);
        assertThat(expected).isEqualTo(cluster.log(leader).lastIndex());
        for (NodeId follower : cluster.followers()) {
            assertThat(cluster.commitIndex(follower)).isEqualTo(expected);
        }
    }

    @Test
    @DisplayName("only the leader accepts proposals")
    void aFollowerRefusesProposals() {
        TestCluster cluster = TestCluster.of(3);
        cluster.tick(100);

        assertThat(cluster.propose(cluster.followers().getFirst(), "nope")).isFalse();
    }

    @Test
    @DisplayName("a follower that missed entries catches up once it can hear the leader again")
    void aLaggingFollowerCatchesUp() {
        TestCluster cluster = TestCluster.of(3);
        cluster.tick(100);
        NodeId leader = cluster.singleLeader();
        NodeId lagging = cluster.followers().getFirst();

        cluster.isolate(lagging);
        for (int i = 0; i < 20; i++) {
            cluster.propose(leader, "v" + i);
        }
        cluster.tick(20);
        assertThat(cluster.logTerms(lagging)).isNotEqualTo(cluster.logTerms(leader));

        cluster.heal();
        cluster.tick(50);

        assertThat(cluster.logTerms(lagging)).isEqualTo(cluster.logTerms(leader));
        assertThat(cluster.commitIndex(lagging)).isEqualTo(cluster.commitIndex(leader));
    }

    @Test
    @DisplayName("entries survive a leader change and are never reordered")
    void committedEntriesSurviveALeaderChange() {
        TestCluster cluster = TestCluster.of(5);
        cluster.tick(100);
        NodeId firstLeader = cluster.singleLeader();
        cluster.propose(firstLeader, "before");
        cluster.tick(20);

        cluster.isolate(firstLeader);
        cluster.tick(100);
        NodeId secondLeader = cluster.singleLeader();
        cluster.propose(secondLeader, "after");
        cluster.tick(50);

        List<String> values = cluster.appliedOf(secondLeader).stream()
                .filter(entry -> entry.type() == EntryType.NORMAL)
                .map(entry -> entry.data().toUtf8())
                .toList();
        assertThat(values).containsExactly("before", "after");
    }
}
