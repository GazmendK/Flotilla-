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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BackpressureTest {

    private static final NodeId N1 = NodeId.of("n1");
    private static final int CAPACITY = 8;

    @TempDir
    Path directory;

    private final RecordingStateMachine stateMachine = new RecordingStateMachine();

    private RaftServer start() {
        return RaftServer.start(
                ServerConfig.defaults().withTickInterval(Duration.ofMillis(5)).withEventQueueCapacity(CAPACITY),
                RaftConfig.defaults(N1),
                ClusterConfig.ofVoters(N1),
                StorageConfig.of(directory).withFsyncPolicy(FsyncPolicy.NEVER),
                stateMachine,
                MessageSink.discarding());
    }

    @Test
    @DisplayName("a stalled state machine makes the server reject proposals rather than buffer them without bound")
    void overloadIsRejectedNotBuffered() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);

        try (RaftServer server = start()) {
            assertThat(server.awaitLeadership(Duration.ofSeconds(10))).isTrue();
            stateMachine.blockUntilReleased(gate);

            List<CompletableFuture<Long>> submitted = new ArrayList<>();
            for (int i = 0; i < CAPACITY * 20; i++) {
                submitted.add(server.propose(Bytes.ofUtf8("v" + i)));
            }

            long rejected = submitted.stream()
                    .filter(CompletableFuture::isCompletedExceptionally)
                    .count();

            assertThat(rejected)
                    .as("a queue of %d cannot possibly have absorbed %d proposals", CAPACITY, submitted.size())
                    .isPositive();
            assertThat(server.events().rejectedProposals()).isEqualTo(rejected);
            assertThat(server.events().depth()).isLessThanOrEqualTo(CAPACITY);

            gate.countDown();
            stateMachine.stopBlocking();
        }
    }

    @Test
    @DisplayName("the rejection names the limit it hit, so an operator knows which knob to turn")
    void theRejectionExplainsItself() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);

        try (RaftServer server = start()) {
            assertThat(server.awaitLeadership(Duration.ofSeconds(10))).isTrue();
            stateMachine.blockUntilReleased(gate);

            Throwable rejection = null;
            for (int i = 0; i < CAPACITY * 20 && rejection == null; i++) {
                CompletableFuture<Long> future = server.propose(Bytes.ofUtf8("v" + i));
                if (future.isCompletedExceptionally()) {
                    rejection = catchCause(future);
                }
            }

            assertThat(rejection)
                    .isInstanceOf(BackpressureException.class)
                    .hasMessageContaining("capacity " + CAPACITY)
                    .hasMessageContaining("rejected rather than buffered");

            gate.countDown();
            stateMachine.stopBlocking();
        }
    }

    private static Throwable catchCause(CompletableFuture<Long> future) {
        try {
            future.get(1, TimeUnit.SECONDS);
            return new AssertionError("expected the future to have failed");
        } catch (Exception failure) {
            return failure.getCause() == null ? failure : failure.getCause();
        }
    }
}
