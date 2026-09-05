/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.flotilla.core.Bytes;
import dev.flotilla.core.ClusterConfig;
import dev.flotilla.core.NodeId;
import dev.flotilla.core.RaftConfig;
import dev.flotilla.storage.FsyncPolicy;
import dev.flotilla.storage.StorageConfig;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServerLifecycleTest {

    private static final NodeId N1 = NodeId.of("n1");
    private static final Duration PATIENCE = Duration.ofSeconds(10);

    @TempDir
    Path directory;

    private RaftServer start() {
        return RaftServer.start(
                ServerConfig.defaults().withTickInterval(Duration.ofMillis(5)),
                RaftConfig.defaults(N1),
                ClusterConfig.ofVoters(N1),
                StorageConfig.of(directory).withFsyncPolicy(FsyncPolicy.NEVER),
                new RecordingStateMachine(),
                MessageSink.discarding());
    }

    private static Set<String> flotillaThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .map(Thread::getName)
                .filter(name -> name.startsWith("flotilla-"))
                .collect(Collectors.toSet());
    }

    @Test
    @DisplayName("closing twice is not an error")
    void closingIsIdempotent() {
        RaftServer server = start();
        server.close();

        assertThatCode(server::close).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("shutting down leaves no threads behind")
    void shutdownLeavesNoThreads() throws Exception {
        RaftServer server = start();
        assertThat(server.awaitLeadership(PATIENCE)).isTrue();
        assertThat(flotillaThreads()).isNotEmpty();

        server.close();
        Thread.sleep(200);

        assertThat(flotillaThreads()).isEmpty();
    }

    @Test
    @DisplayName("a proposal after shutdown fails immediately instead of hanging forever")
    void proposalsAfterShutdownFailFast() {
        RaftServer server = start();
        assertThat(server.awaitLeadership(PATIENCE)).isTrue();
        server.close();

        assertThatThrownBy(() -> server.propose(Bytes.ofUtf8("late")).get(2, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("shutting down under load completes and fails the proposals still in flight")
    void shutdownUnderLoadCompletes() {
        RaftServer server = start();
        assertThat(server.awaitLeadership(PATIENCE)).isTrue();

        List<CompletableFuture<Long>> submitted = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            submitted.add(server.propose(Bytes.ofUtf8("v" + i)));
        }
        server.close();

        assertThat(server.pendingProposals()).isZero();
        assertThat(submitted)
                .as("no caller may be left waiting on a future that nothing will ever complete")
                .allMatch(CompletableFuture::isDone);
        assertThat(flotillaThreads()).isEmpty();
    }

    @Test
    @DisplayName("the event loop reports a failure rather than dying silently")
    void aHealthyServerReportsNoFailure() {
        try (RaftServer server = start()) {
            assertThat(server.awaitLeadership(PATIENCE)).isTrue();

            assertThat(server.failure()).isEmpty();
        }
    }
}
