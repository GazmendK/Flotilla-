/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.core;

import static org.assertj.core.api.Assertions.assertThat;

import dev.flotilla.core.message.RaftMessage;
import dev.flotilla.core.message.RequestVoteResponse;
import dev.flotilla.core.port.RandomSource;
import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class Figure8Test {

    private static final NodeId N1 = NodeId.of("n1");
    private static final NodeId N2 = NodeId.of("n2");
    private static final NodeId N3 = NodeId.of("n3");
    private static final ClusterConfig CLUSTER = ClusterConfig.ofVoters(N1, N2, N3);

    private static RaftNode nodeWith(NodeId id, long startingTerm, long... logTerms) {
        InMemoryLogStore log = new InMemoryLogStore();
        if (logTerms.length > 0) {
            log.append(LongStream.range(0, logTerms.length)
                    .mapToObj(offset -> LogEntry.noOp(logTerms[(int) offset], offset + 1))
                    .toList());
        }
        return new RaftNode(
                RaftConfig.defaults(id),
                CLUSTER,
                log,
                RandomSource.seeded(id.value().hashCode()),
                new HardState(startingTerm, null, 0));
    }

    private static List<RaftMessage> drain(RaftNode node) {
        List<RaftMessage> messages = List.copyOf(node.ready().messagesToSend());
        node.advance();
        return messages;
    }

    private static void exchange(RaftNode leader, RaftNode follower) {
        for (RaftMessage message : drain(leader)) {
            if (message.to().equals(follower.id())) {
                follower.step(message);
            }
        }
        for (RaftMessage message : drain(follower)) {
            if (message.to().equals(leader.id())) {
                leader.step(message);
            }
        }
    }

    private static RaftNode electedLeaderWithLog(long... logTerms) {
        RaftNode node = nodeWith(N1, 2, logTerms);
        node.campaign();
        drain(node);
        node.step(new RequestVoteResponse(N2, N1, 3, true, true));
        drain(node);
        node.step(new RequestVoteResponse(N2, N1, 3, true, false));
        return node;
    }

    @Test
    @DisplayName("an entry from an earlier term is not committed just because a majority stores it")
    void anEarlierTermEntryIsNotCommittedByCountingReplicas() {
        RaftNode leader = electedLeaderWithLog(1, 2);
        RaftNode follower = nodeWith(N2, 2, 1);
        assertThat(leader.isLeader()).isTrue();
        assertThat(leader.currentTerm()).isEqualTo(3);

        exchange(leader, follower);
        exchange(leader, follower);

        assertThat(follower.lastLogIndex())
                .as("the entry from term 2 is now stored by the leader and a follower, a majority of three")
                .isEqualTo(2);
        assertThat(leader.commitIndex())
                .as("committing it here is the Figure 8 mistake: a later leader could still overwrite it")
                .isZero();
    }

    @Test
    @DisplayName("once an entry of the leader's own term commits, the earlier entries commit with it")
    void theCurrentTermEntryCommitsTheEarlierOnesTransitively() {
        RaftNode leader = electedLeaderWithLog(1, 2);
        RaftNode follower = nodeWith(N2, 2, 1);

        exchange(leader, follower);
        exchange(leader, follower);
        exchange(leader, follower);

        assertThat(leader.commitIndex()).isEqualTo(3);
        assertThat(leader.ready().committedEntriesToApply())
                .extracting(LogEntry::index)
                .containsExactly(1L, 2L, 3L);
    }

    @Test
    @DisplayName("the no-op a leader appends on election is what makes any commit possible at all")
    void aLeaderCommitsNothingWithoutAnEntryOfItsOwnTerm() {
        RaftNode leader = electedLeaderWithLog(1, 1, 1);

        assertThat(leader.lastLogIndex()).isEqualTo(4);
        assertThat(leader.commitIndex())
                .as("nothing is replicated yet, so nothing is committed")
                .isZero();
    }

    @Test
    @DisplayName("the commit index never moves backwards")
    void theCommitIndexIsMonotonic() {
        TestCluster cluster = TestCluster.of(3);
        cluster.tick(100);
        NodeId leader = cluster.singleLeader();

        long highest = 0;
        for (int i = 0; i < 10; i++) {
            cluster.propose(leader, "v" + i);
            cluster.tick(5);
            for (NodeId node : List.of(N1, N2, N3)) {
                assertThat(cluster.commitIndex(node)).isGreaterThanOrEqualTo(0);
            }
            long current = cluster.commitIndex(leader);
            assertThat(current).isGreaterThanOrEqualTo(highest);
            highest = current;
        }
        assertThat(highest).isPositive();
    }
}
