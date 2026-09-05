/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.flotilla.core.Bytes;
import dev.flotilla.core.ClusterConfig;
import dev.flotilla.core.NodeId;
import dev.flotilla.core.RaftConfig;
import dev.flotilla.storage.FsyncPolicy;
import dev.flotilla.storage.StorageConfig;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SingleNodeServerTest {

    private static final NodeId N1 = NodeId.of("n1");
    private static final Duration PATIENCE = Duration.ofSeconds(10);

    @TempDir
    Path directory;

    private final RecordingStateMachine stateMachine = new RecordingStateMachine();

    private RaftServer start(ClusterConfig cluster) {
        return RaftServer.start(
                ServerConfig.defaults().withTickInterval(Duration.ofMillis(5)),
                RaftConfig.defaults(N1),
                cluster,
                StorageConfig.of(directory).withFsyncPolicy(FsyncPolicy.NEVER),
                stateMachine,
                MessageSink.discarding());
    }

    @Test
    @DisplayName("a single node elects itself and the process actually runs")
    void aSingleNodeBecomesLeader() {
        try (RaftServer server = start(ClusterConfig.ofVoters(N1))) {
            assertThat(server.awaitLeadership(PATIENCE)).isTrue();
            assertThat(server.currentTerm()).isPositive();
            assertThat(server.failure()).isEmpty();
        }
    }

    @Test
    @DisplayName("a proposal is durable and applied before its future completes")
    void aProposalIsAppliedBeforeItsFutureCompletes() throws Exception {
        try (RaftServer server = start(ClusterConfig.ofVoters(N1))) {
            assertThat(server.awaitLeadership(PATIENCE)).isTrue();

            long index = server.propose(Bytes.ofUtf8("hello")).get(10, TimeUnit.SECONDS);

            assertThat(stateMachine.applied()).contains("hello");
            assertThat(stateMachine.indexes()).contains(index);
            assertThat(server.commitIndex()).isGreaterThanOrEqualTo(index);
        }
    }

    @Test
    @DisplayName("proposals are applied exactly once, in order")
    void proposalsAreAppliedInOrder() throws Exception {
        try (RaftServer server = start(ClusterConfig.ofVoters(N1))) {
            assertThat(server.awaitLeadership(PATIENCE)).isTrue();

            for (int i = 0; i < 25; i++) {
                server.propose(Bytes.ofUtf8("v" + i)).get(10, TimeUnit.SECONDS);
            }

            assertThat(stateMachine.applied()).hasSize(25);
            assertThat(stateMachine.indexes()).doesNotHaveDuplicates().isSorted();
        }
    }

    @Test
    @DisplayName("a node that cannot win an election refuses proposals instead of hanging")
    void aNodeWithoutAMajorityRefusesProposals() {
        ClusterConfig unreachableCluster = ClusterConfig.ofVoters(N1, NodeId.of("n2"), NodeId.of("n3"));

        try (RaftServer server = start(unreachableCluster)) {
            assertThat(server.awaitLeadership(Duration.ofMillis(500))).isFalse();

            assertThatThrownBy(() -> server.propose(Bytes.ofUtf8("nope")).get(5, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(NotLeaderException.class);
        }
    }

    @Test
    @DisplayName("the leader writes a no-op of its own term, so the commit index moves without any client")
    void theLeaderCommitsItsOwnNoOp() {
        try (RaftServer server = start(ClusterConfig.ofVoters(N1))) {
            assertThat(server.awaitLeadership(PATIENCE)).isTrue();

            assertThat(server.commitIndex()).isPositive();
            assertThat(stateMachine.applied()).isEmpty();
        }
    }
}
