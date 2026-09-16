/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.flotilla.core.message.AppendEntriesRequest;
import dev.flotilla.core.message.AppendEntriesResponse;
import dev.flotilla.core.message.RaftMessage;
import dev.flotilla.core.message.RequestVoteRequest;
import dev.flotilla.core.message.RequestVoteResponse;
import dev.flotilla.core.port.RandomSource;
import dev.flotilla.core.port.SnapshotStore;
import java.util.List;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ReadIndexTest {

    private static final NodeId N1 = NodeId.of("n1");
    private static final NodeId N2 = NodeId.of("n2");
    private static final NodeId N3 = NodeId.of("n3");
    private static final ClusterConfig TRIO = ClusterConfig.ofVoters(N1, N2, N3);
    private static final Bytes READ = Bytes.ofUtf8("read-1");

    private final InMemoryLogStore log = new InMemoryLogStore();

    private RaftNode node(ClusterConfig cluster, UnaryOperator<RaftConfig.Builder> tuning) {
        return new RaftNode(
                tuning.apply(RaftConfig.builder(N1)).build(),
                cluster,
                log,
                SnapshotStore.none(),
                RandomSource.seeded(11),
                HardState.INITIAL);
    }

    private RaftNode electedLeader(UnaryOperator<RaftConfig.Builder> tuning) {
        RaftNode node = node(TRIO, tuning);
        node.campaign();
        node.step(new RequestVoteResponse(N2, N1, 1, true, true));
        node.step(new RequestVoteResponse(N2, N1, 1, true, false));
        assertThat(node.isLeader()).isTrue();
        drain(node);
        return node;
    }

    private RaftNode committedLeader(UnaryOperator<RaftConfig.Builder> tuning) {
        RaftNode leader = electedLeader(tuning);
        leader.step(AppendEntriesResponse.accepted(N2, N1, 1, 1, 0));
        assertThat(leader.commitIndex()).isEqualTo(1);
        drain(leader);
        return leader;
    }

    private static List<RaftMessage> drain(RaftNode node) {
        List<RaftMessage> messages = List.copyOf(node.ready().messagesToSend());
        node.advance();
        return messages;
    }

    private static long latestRound(List<RaftMessage> messages) {
        return messages.stream()
                .filter(AppendEntriesRequest.class::isInstance)
                .map(AppendEntriesRequest.class::cast)
                .mapToLong(AppendEntriesRequest::round)
                .max()
                .orElseThrow(() -> new AssertionError("no append or heartbeat was sent in " + messages));
    }

    private static void ack(RaftNode leader, NodeId from, long round) {
        leader.step(AppendEntriesResponse.accepted(from, N1, leader.currentTerm(), 1, round));
    }

    @Test
    @DisplayName("a read on a new leader waits until the leader has committed an entry of its own term")
    void aReadWaitsForTheNoOpCommit() {
        RaftNode leader = electedLeader(UnaryOperator.identity());

        assertThat(leader.readIndex(READ)).isTrue();
        assertThat(leader.ready().readStates())
                .as("before its no-op commits, a new leader does not know the true commit index")
                .isEmpty();
        assertThat(drain(leader)).noneMatch(AppendEntriesRequest.class::isInstance);

        leader.step(AppendEntriesResponse.accepted(N2, N1, 1, 1, 0));
        long round = latestRound(drain(leader));
        assertThat(leader.ready().readStates()).isEmpty();

        ack(leader, N2, round);

        assertThat(leader.ready().readStates()).containsExactly(new ReadState(READ, 1));
    }

    @Test
    @DisplayName("an acknowledgement of a heartbeat sent before the read does not confirm the read")
    void anOlderAcknowledgementDoesNotCount() {
        RaftNode leader = committedLeader(UnaryOperator.identity());
        leader.tick();
        long before = latestRound(drain(leader));

        leader.readIndex(READ);
        long after = latestRound(drain(leader));
        assertThat(after).isGreaterThan(before);

        ack(leader, N2, before);
        assertThat(leader.ready().readStates())
                .as("that follower may have recognised this leader before another one was elected")
                .isEmpty();

        ack(leader, N2, after);
        assertThat(leader.ready().readStates()).containsExactly(new ReadState(READ, 1));
    }

    @Test
    @DisplayName("a leader deposed while a read is in flight never answers it")
    void aDeposedLeaderNeverAnswers() {
        RaftNode leader = committedLeader(UnaryOperator.identity());
        leader.readIndex(READ);
        long round = latestRound(drain(leader));

        leader.step(AppendEntriesResponse.rejected(N2, N1, 5, 0, 0, round));
        ack(leader, N3, round);

        assertThat(leader.isLeader()).isFalse();
        assertThat(leader.ready().readStates()).isEmpty();
    }

    @Test
    @DisplayName("reads arriving while a round is in flight share the next round instead of each sending one")
    void readsAreBatched() {
        RaftNode leader = committedLeader(UnaryOperator.identity());
        leader.readIndex(Bytes.ofUtf8("a"));
        long first = latestRound(drain(leader));

        leader.readIndex(Bytes.ofUtf8("b"));
        leader.readIndex(Bytes.ofUtf8("c"));
        assertThat(drain(leader)).noneMatch(AppendEntriesRequest.class::isInstance);

        ack(leader, N2, first);
        assertThat(leader.ready().readStates()).containsExactly(new ReadState(Bytes.ofUtf8("a"), 1));
        long second = latestRound(drain(leader));

        ack(leader, N3, second);
        assertThat(leader.ready().readStates())
                .containsExactly(new ReadState(Bytes.ofUtf8("b"), 1), new ReadState(Bytes.ofUtf8("c"), 1));
    }

    @Test
    @DisplayName("a single node is its own majority and answers at once")
    void aSingleNodeAnswersImmediately() {
        RaftNode solo = node(ClusterConfig.ofVoters(N1), UnaryOperator.identity());
        solo.campaign();
        drain(solo);

        solo.readIndex(READ);

        assertThat(solo.ready().readStates()).containsExactly(new ReadState(READ, 1));
    }

    @Test
    @DisplayName("a candidate cannot serve a read, and says so rather than queueing it")
    void aCandidateRefuses() {
        RaftNode candidate = node(TRIO, UnaryOperator.identity());
        candidate.campaign();

        assertThat(candidate.readIndex(READ)).isFalse();
    }

    @Test
    @DisplayName("a follower read is answered with the leader's commit index, including writes it has not seen")
    void aFollowerReadUsesTheLeadersIndex() {
        TestCluster cluster = TestCluster.of(3);
        cluster.tick(100);
        NodeId leader = cluster.singleLeader();
        NodeId follower = cluster.followers().getFirst();
        cluster.propose(leader, "x");
        long written = cluster.commitIndex(leader);

        assertThat(cluster.node(follower).readIndex(READ)).isTrue();
        cluster.deliver();

        assertThat(cluster.readStatesOf(follower)).containsExactly(new ReadState(READ, written));
        assertThat(cluster.readStatesOf(leader)).isEmpty();
    }

    @Test
    @DisplayName("a follower that knows no leader refuses a read instead of guessing")
    void aFollowerWithoutALeaderRefuses() {
        assertThat(node(TRIO, UnaryOperator.identity()).readIndex(READ)).isFalse();
    }

    @Test
    @DisplayName("lease reads are off unless asked for, because their safety depends on clocks")
    void leaseReadsAreOffByDefault() {
        RaftNode leader = committedLeader(UnaryOperator.identity());
        leader.tick();
        ack(leader, N2, latestRound(drain(leader)));

        leader.leaseRead(READ);

        assertThat(leader.ready().readStates()).isEmpty();
        assertThat(drain(leader))
                .as("without a lease the read falls back to a confirmation round")
                .anyMatch(AppendEntriesRequest.class::isInstance);
    }

    @Test
    @DisplayName("while a majority was heard from recently, a lease read answers without sending anything")
    void aLeaseReadSkipsTheRound() {
        RaftNode leader = committedLeader(builder -> builder.leaseReads(true));
        leader.tick();
        ack(leader, N2, latestRound(drain(leader)));

        leader.leaseRead(READ);

        assertThat(leader.ready().readStates()).containsExactly(new ReadState(READ, 1));
        assertThat(drain(leader)).noneMatch(AppendEntriesRequest.class::isInstance);
    }

    @Test
    @DisplayName("a lease runs out before any follower could vote for someone else, and reads fall back")
    void aLeaseExpiresInTime() {
        RaftConfig config = RaftConfig.builder(N1).leaseReads(true).build();
        RaftNode leader = committedLeader(builder -> builder.leaseReads(true));
        leader.tick();
        ack(leader, N2, latestRound(drain(leader)));

        for (int tick = 0; tick < config.leaseTicks() - 1; tick++) {
            leader.tick();
        }
        drain(leader);
        leader.leaseRead(Bytes.ofUtf8("inside"));
        assertThat(leader.ready().readStates()).containsExactly(new ReadState(Bytes.ofUtf8("inside"), 1));
        drain(leader);

        leader.tick();
        drain(leader);
        leader.leaseRead(Bytes.ofUtf8("outside"));

        assertThat(leader.isLeader()).isTrue();
        assertThat(leader.ready().readStates())
                .as(
                        "%d ticks after the last acknowledged round the lease of %d ticks is gone",
                        config.leaseTicks(), config.leaseTicks())
                .isEmpty();
    }

    @Test
    @DisplayName("with leases on, a freshly started node refuses votes until its own election timeout has passed")
    void aRestartedNodeDoesNotVoteDuringItsQuietPeriod() {
        RaftNode withLeases = node(TRIO, builder -> builder.leaseReads(true));
        withLeases.step(new RequestVoteRequest(N2, N1, 1, 0, 0, true));
        assertThat(drain(withLeases))
                .as("it may have promised a lease to a leader before it crashed and cannot remember")
                .containsExactly(new RequestVoteResponse(N1, N2, 0, false, true));

        RaftNode withoutLeases = node(TRIO, UnaryOperator.identity());
        withoutLeases.step(new RequestVoteRequest(N2, N1, 1, 0, 0, true));
        assertThat(drain(withoutLeases)).containsExactly(new RequestVoteResponse(N1, N2, 1, true, true));

        RaftConfig config = RaftConfig.builder(N1).build();
        for (int tick = 0; tick < config.electionTimeoutMinTicks(); tick++) {
            withLeases.tick();
        }
        drain(withLeases);
        withLeases.step(new RequestVoteRequest(N2, N1, 1, 0, 0, true));
        assertThat(drain(withLeases)).containsExactly(new RequestVoteResponse(N1, N2, 1, true, true));
    }

    @Test
    void leasesWithoutCheckQuorumAreRefused() {
        assertThatThrownBy(() -> RaftConfig.builder(N1)
                        .leaseReads(true)
                        .checkQuorum(false)
                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("leaseReads requires checkQuorum");
    }

    @Test
    void aDriftBoundAsLongAsTheElectionTimeoutIsRefused() {
        assertThatThrownBy(() -> RaftConfig.builder(N1).clockDriftBoundTicks(10).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("clockDriftBoundTicks");
    }
}
