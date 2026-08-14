/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.core;

import static org.assertj.core.api.Assertions.assertThat;

import dev.flotilla.core.message.AppendEntriesRequest;
import dev.flotilla.core.message.RaftMessage;
import dev.flotilla.core.message.RequestVoteRequest;
import dev.flotilla.core.message.RequestVoteResponse;
import dev.flotilla.core.port.RandomSource;
import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class VoteRulesTest {

    private static final NodeId N1 = NodeId.of("n1");
    private static final NodeId N2 = NodeId.of("n2");
    private static final NodeId N3 = NodeId.of("n3");
    private static final ClusterConfig CLUSTER = ClusterConfig.ofVoters(N1, N2, N3);

    private final InMemoryLogStore log = new InMemoryLogStore();

    private RaftNode nodeAtTerm(long term) {
        return new RaftNode(
                RaftConfig.defaults(N1), CLUSTER, log, RandomSource.seeded(1), new HardState(term, null, 0));
    }

    private void fillLog(long term, int count) {
        log.append(LongStream.rangeClosed(1, count)
                .mapToObj(index -> LogEntry.noOp(term, index))
                .toList());
    }

    private static List<RaftMessage> drain(RaftNode node) {
        List<RaftMessage> messages = List.copyOf(node.ready().messagesToSend());
        node.advance();
        return messages;
    }

    private static RequestVoteResponse voteResponse(RaftNode node) {
        return drain(node).stream()
                .filter(RequestVoteResponse.class::isInstance)
                .map(RequestVoteResponse.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no vote response was produced"));
    }

    @Test
    void grantsAVoteToAnUpToDateCandidate() {
        RaftNode node = nodeAtTerm(0);

        node.step(new RequestVoteRequest(N2, N1, 1, 0, 0, false));

        assertThat(voteResponse(node).voteGranted()).isTrue();
        assertThat(node.votedFor()).contains(N2);
    }

    @Test
    @DisplayName("a candidate whose log is shorter at the same term is refused")
    void rejectsACandidateWithAShorterLog() {
        fillLog(1, 3);
        RaftNode node = nodeAtTerm(1);

        node.step(new RequestVoteRequest(N2, N1, 2, 1, 1, false));

        assertThat(voteResponse(node).voteGranted()).isFalse();
    }

    @Test
    @DisplayName("last term outranks last index: a shorter log from a newer term is still more up to date")
    void prefersTheLastTermOverTheLastIndex() {
        fillLog(1, 3);
        RaftNode node = nodeAtTerm(1);

        node.step(new RequestVoteRequest(N2, N1, 3, 1, 2, false));

        assertThat(voteResponse(node).voteGranted()).isTrue();
    }

    @Test
    void rejectsACandidateWhoseLastTermIsOlder() {
        fillLog(3, 2);
        RaftNode node = nodeAtTerm(3);

        node.step(new RequestVoteRequest(N2, N1, 4, 99, 2, false));

        assertThat(voteResponse(node).voteGranted()).isFalse();
    }

    @Test
    @DisplayName("a server votes at most once per term")
    void votesOnlyOncePerTerm() {
        RaftNode node = nodeAtTerm(0);
        node.step(new RequestVoteRequest(N2, N1, 1, 0, 0, false));
        assertThat(voteResponse(node).voteGranted()).isTrue();

        node.step(new RequestVoteRequest(N3, N1, 1, 0, 0, false));

        assertThat(voteResponse(node).voteGranted()).isFalse();
        assertThat(node.votedFor()).contains(N2);
    }

    @Test
    @DisplayName("re-asking after a lost response is granted again, so a retry is not a second vote")
    void repeatingTheSameRequestIsIdempotent() {
        RaftNode node = nodeAtTerm(0);
        node.step(new RequestVoteRequest(N2, N1, 1, 0, 0, false));
        drain(node);

        node.step(new RequestVoteRequest(N2, N1, 1, 0, 0, false));

        assertThat(voteResponse(node).voteGranted()).isTrue();
    }

    @Test
    @DisplayName("a message from an older term is refused and answered with the current term")
    void rejectsAMessageFromAnOlderTerm() {
        RaftNode node = nodeAtTerm(5);

        node.step(new RequestVoteRequest(N2, N1, 3, 0, 0, false));

        RequestVoteResponse response = voteResponse(node);
        assertThat(response.voteGranted()).isFalse();
        assertThat(response.term()).isEqualTo(5);
        assertThat(node.currentTerm()).isEqualTo(5);
    }

    @Test
    @DisplayName("a higher term always wins, and the vote of the old term is forgotten")
    void adoptsAHigherTermAndForgetsItsVote() {
        RaftNode node = nodeAtTerm(0);
        node.step(new RequestVoteRequest(N2, N1, 1, 0, 0, false));
        drain(node);
        assertThat(node.votedFor()).contains(N2);

        node.step(new AppendEntriesRequest(N3, N1, 9, 0, 0, List.of(), 0));

        assertThat(node.currentTerm()).isEqualTo(9);
        assertThat(node.role()).isEqualTo(RaftRole.FOLLOWER);
        assertThat(node.leader()).contains(N3);
        assertThat(node.votedFor()).isEmpty();
    }
}
