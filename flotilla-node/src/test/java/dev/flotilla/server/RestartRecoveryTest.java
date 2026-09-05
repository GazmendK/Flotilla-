/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.server;

import static org.assertj.core.api.Assertions.assertThat;

import dev.flotilla.core.Bytes;
import dev.flotilla.core.ClusterConfig;
import dev.flotilla.core.NodeId;
import dev.flotilla.core.RaftConfig;
import dev.flotilla.storage.FsyncPolicy;
import dev.flotilla.storage.StorageConfig;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RestartRecoveryTest {

    private static final NodeId N1 = NodeId.of("n1");
    private static final Duration PATIENCE = Duration.ofSeconds(10);

    @TempDir
    Path directory;

    private RaftServer start(RecordingStateMachine stateMachine) {
        return RaftServer.start(
                ServerConfig.defaults().withTickInterval(Duration.ofMillis(5)),
                RaftConfig.defaults(N1),
                ClusterConfig.ofVoters(N1),
                StorageConfig.of(directory).withFsyncPolicy(FsyncPolicy.ALWAYS),
                stateMachine,
                MessageSink.discarding());
    }

    @Test
    @DisplayName("a restarted node replays its log into a fresh state machine")
    void aRestartRebuildsTheStateMachine() throws Exception {
        long lastIndex;
        try (RaftServer server = start(new RecordingStateMachine())) {
            assertThat(server.awaitLeadership(PATIENCE)).isTrue();
            server.propose(Bytes.ofUtf8("alpha")).get(10, TimeUnit.SECONDS);
            lastIndex = server.propose(Bytes.ofUtf8("beta")).get(10, TimeUnit.SECONDS);
        }

        RecordingStateMachine rebuilt = new RecordingStateMachine();
        try (RaftServer restarted = start(rebuilt)) {
            assertThat(rebuilt.applied())
                    .as("the state machine must be rebuilt from the log before anything else runs")
                    .containsExactly("alpha", "beta");
            assertThat(restarted.appliedIndex()).isGreaterThanOrEqualTo(lastIndex);
        }
    }

    @Test
    @DisplayName("a restarted node keeps its term and does not start from zero")
    void aRestartKeepsItsTerm() {
        long termBefore;
        try (RaftServer server = start(new RecordingStateMachine())) {
            assertThat(server.awaitLeadership(PATIENCE)).isTrue();
            termBefore = server.currentTerm();
        }

        try (RaftServer restarted = start(new RecordingStateMachine())) {
            assertThat(restarted.awaitLeadership(PATIENCE)).isTrue();

            assertThat(restarted.currentTerm())
                    .as("a node that forgets its term can vote twice in it")
                    .isGreaterThan(termBefore);
        }
    }

    @Test
    @DisplayName("entries proposed before a restart are still committed after it")
    void committedEntriesSurviveARestart() throws Exception {
        try (RaftServer server = start(new RecordingStateMachine())) {
            assertThat(server.awaitLeadership(PATIENCE)).isTrue();
            for (int i = 0; i < 10; i++) {
                server.propose(Bytes.ofUtf8("v" + i)).get(10, TimeUnit.SECONDS);
            }
        }

        RecordingStateMachine rebuilt = new RecordingStateMachine();
        try (RaftServer restarted = start(rebuilt)) {
            assertThat(rebuilt.applied()).hasSize(10);
            assertThat(restarted.awaitLeadership(PATIENCE)).isTrue();

            long index = restarted.propose(Bytes.ofUtf8("after")).get(10, TimeUnit.SECONDS);
            assertThat(rebuilt.applied()).hasSize(11).endsWith("after");
            assertThat(index).isGreaterThan(10);
        }
    }
}
