/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.core;

import static org.assertj.core.api.Assertions.assertThat;

import dev.flotilla.core.message.RaftMessage;
import dev.flotilla.core.message.TimeoutNowRequest;
import java.util.List;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LeaderTransferTest {

    private static final int ELECTION_TIMEOUT =
            RaftConfig.defaults(NodeId.of("n1")).electionTimeoutMinTicks();

    private static TestCluster trio() {
        TestCluster cluster = TestCluster.of(3, builder -> builder.leaseReads(true));
        cluster.tick(100);
        return cluster;
    }

    private static String reason(TransferResult result) {
        assertThat(result).isInstanceOf(TransferResult.Rejected.class);
        return ((TransferResult.Rejected) result).reason();
    }

    @Test
    @DisplayName("leadership moves to a caught-up follower at once, without waiting for an election timeout")
    void leadershipMovesToTheTarget() {
        TestCluster cluster = trio();
        NodeId leader = cluster.singleLeader();
        NodeId target = cluster.followers().getFirst();
        long term = cluster.term(leader);

        TransferResult result = cluster.node(leader).transferLeadership(target);
        cluster.deliver();

        assertThat(result).isEqualTo(new TransferResult.Started(target));
        assertThat(cluster.singleLeader())
                .as("the other voters heard from the old leader a moment ago and would ignore an ordinary candidate")
                .isEqualTo(target);
        assertThat(cluster.term(target)).isEqualTo(term + 1);
        assertThat(cluster.role(leader)).isEqualTo(RaftRole.FOLLOWER);
    }

    @Test
    @DisplayName("a target that is behind is brought up to date first, then told to campaign")
    void aLaggingTargetCatchesUpFirst() {
        TestCluster cluster = trio();
        NodeId leader = cluster.singleLeader();
        NodeId target = cluster.followers().getFirst();
        cluster.isolate(target);
        cluster.propose(leader, "missed");
        cluster.propose(leader, "also missed");
        cluster.heal();

        cluster.node(leader).transferLeadership(target);
        cluster.deliver();

        assertThat(cluster.singleLeader()).isEqualTo(target);
        assertThat(cluster.appliedOf(target).stream()
                        .map(entry -> entry.data().toUtf8())
                        .toList())
                .contains("missed", "also missed");
    }

    @Test
    @DisplayName("the leader refuses new work while it hands over, and resumes if the handover takes too long")
    void aStalledTransferIsAbandoned() {
        TestCluster cluster = trio();
        NodeId leader = cluster.singleLeader();
        NodeId target = cluster.followers().getFirst();
        cluster.isolate(target);

        assertThat(cluster.node(leader).transferLeadership(target).isStarted()).isTrue();
        assertThat(cluster.node(leader).transferee()).contains(target);
        assertThat(cluster.node(leader).propose(Bytes.ofUtf8("during")))
                .as("an entry accepted now might never reach the target before it campaigns")
                .isFalse();
        assertThat(cluster.node(leader).proposeConfChange(new ConfChange.Remove(target)))
                .isInstanceOf(ConfChangeResult.Rejected.class);

        cluster.tick(ELECTION_TIMEOUT);

        assertThat(cluster.singleLeader()).isEqualTo(leader);
        assertThat(cluster.node(leader).transferee()).isEmpty();
        assertThat(cluster.propose(leader, "after")).isTrue();
    }

    @Test
    @DisplayName("only a voter other than the leader can be handed leadership, and only one handover at a time")
    void invalidTransfersAreRejected() {
        TestCluster cluster = TestCluster.withMembers(
                new ClusterConfig(
                        new TreeSet<>(List.of(NodeId.of("n1"), NodeId.of("n2"), NodeId.of("n3"))),
                        new TreeSet<>(List.of(NodeId.of("n4")))),
                List.of(NodeId.of("n1"), NodeId.of("n2"), NodeId.of("n3"), NodeId.of("n4")));
        cluster.tick(100);
        NodeId leader = cluster.singleLeader();
        NodeId follower = cluster.followers().stream()
                .filter(id -> !id.equals(NodeId.of("n4")))
                .findFirst()
                .orElseThrow();

        assertThat(reason(cluster.node(follower).transferLeadership(leader))).contains("Only the leader");
        assertThat(reason(cluster.node(leader).transferLeadership(leader))).contains("already leads");
        assertThat(reason(cluster.node(leader).transferLeadership(NodeId.of("n4"))))
                .contains("not a voter");
        assertThat(reason(cluster.node(leader).transferLeadership(NodeId.of("n9"))))
                .contains("not a voter");

        cluster.isolate(follower);
        assertThat(cluster.node(leader).transferLeadership(follower).isStarted())
                .isTrue();
        assertThat(reason(cluster.node(leader).transferLeadership(follower))).contains("already being handed");
    }

    @Test
    @DisplayName("TimeoutNow makes a follower campaign at once, skipping PreVote, but only if it comes from its leader")
    void timeoutNowComesFromTheLeaderOnly() {
        TestCluster cluster = trio();
        NodeId leader = cluster.singleLeader();
        NodeId target = cluster.followers().get(0);
        NodeId bystander = cluster.followers().get(1);
        long term = cluster.term(target);

        cluster.node(target).step(new TimeoutNowRequest(bystander, target, term));
        assertThat(cluster.role(target)).isEqualTo(RaftRole.FOLLOWER);

        cluster.node(target).step(new TimeoutNowRequest(leader, target, term));
        assertThat(cluster.role(target))
                .as("a pre-vote would be refused: every voter heard from the leader a moment ago")
                .isEqualTo(RaftRole.CANDIDATE);
        assertThat(cluster.term(target)).isEqualTo(term + 1);
    }

    @Test
    @DisplayName("a leader that has told a follower to campaign never answers from its lease again in that term")
    void aDelayedTimeoutNowCannotMakeALeaseReadStale() {
        TestCluster cluster = trio();
        NodeId leader = cluster.singleLeader();
        NodeId target = cluster.followers().get(0);
        cluster.propose(leader, "old");

        cluster.node(leader).transferLeadership(target);
        Ready ready = cluster.node(leader).ready();
        List<RaftMessage> held = ready.messagesToSend();
        cluster.node(leader).advance();
        assertThat(held).singleElement().isInstanceOf(TimeoutNowRequest.class);

        cluster.isolate(target);
        cluster.tick(ELECTION_TIMEOUT);
        cluster.heal();
        cluster.tick();
        assertThat(cluster.role(leader)).isEqualTo(RaftRole.LEADER);
        assertThat(cluster.node(leader).transferee())
                .as("the transfer was given up")
                .isEmpty();
        assertThat(cluster.node(target).leader()).contains(leader);

        cluster.isolate(leader);
        cluster.node(target).step(held.getFirst());
        cluster.deliver();
        assertThat(cluster.role(target)).isEqualTo(RaftRole.LEADER);
        cluster.propose(target, "new");
        assertThat(cluster.commitIndex(target)).isGreaterThan(cluster.commitIndex(leader));

        Bytes request = Bytes.ofUtf8("read");
        cluster.node(leader).leaseRead(request);
        cluster.deliver();

        assertThat(cluster.readStatesOf(leader))
                .as("the old leader's majority stopped keeping its promise when the TimeoutNow arrived, "
                        + "so a lease read would have returned \"old\" after \"new\" committed")
                .isEmpty();
    }
}
