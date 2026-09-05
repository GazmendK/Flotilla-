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
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ApplyLoopTest {

    private static final NodeId N1 = NodeId.of("n1");
    private static final Duration PATIENCE = Duration.ofSeconds(15);
    private static final int LOAD = 50;

    @TempDir
    Path directory;

    private final RecordingStateMachine stateMachine = new RecordingStateMachine();

    private RaftServer start() {
        return RaftServer.start(
                ServerConfig.defaults().withTickInterval(Duration.ofMillis(5)),
                RaftConfig.defaults(N1),
                ClusterConfig.ofVoters(N1),
                StorageConfig.of(directory).withFsyncPolicy(FsyncPolicy.NEVER),
                stateMachine,
                MessageSink.discarding());
    }

    private static boolean await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + PATIENCE.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.onSpinWait();
        }
        return condition.getAsBoolean();
    }

    @Test
    @DisplayName("a stalled state machine no longer stops the log from committing")
    void applyingIsDecoupledFromReplication() {
        CountDownLatch gate = new CountDownLatch(1);

        try (RaftServer server = start()) {
            assertThat(server.awaitLeadership(PATIENCE)).isTrue();
            long appliedBefore = server.appliedIndex();
            stateMachine.blockUntilReleased(gate);

            List<CompletableFuture<Long>> submitted = new ArrayList<>();
            for (int i = 0; i < LOAD; i++) {
                submitted.add(server.propose(Bytes.ofUtf8("v" + i)));
            }
            assertThat(submitted).noneMatch(CompletableFuture::isDone);

            assertThat(await(() -> server.commitIndex() >= LOAD))
                    .as("consensus must keep running while the state machine is stuck")
                    .isTrue();
            assertThat(server.appliedIndex())
                    .as("the stuck state machine cannot have applied anything")
                    .isEqualTo(appliedBefore);
            assertThat(server.applyBacklog()).isPositive();

            gate.countDown();
            stateMachine.stopBlocking();

            assertThat(server.awaitApplied(LOAD, PATIENCE)).isTrue();
            assertThat(server.applyBacklog()).isZero();
        }
    }

    @Test
    @DisplayName("entries reach the state machine exactly once and in index order")
    void entriesAreAppliedOnceInOrder() throws Exception {
        try (RaftServer server = start()) {
            assertThat(server.awaitLeadership(PATIENCE)).isTrue();

            for (int i = 0; i < LOAD; i++) {
                server.propose(Bytes.ofUtf8("v" + i)).get(15, TimeUnit.SECONDS);
            }

            assertThat(stateMachine.applied()).hasSize(LOAD);
            assertThat(stateMachine.indexes()).isSorted().doesNotHaveDuplicates();
        }
    }

    @Test
    @DisplayName("a proposal is completed by the apply thread, not by the event loop")
    void theFutureCompletesOnApply() throws Exception {
        try (RaftServer server = start()) {
            assertThat(server.awaitLeadership(PATIENCE)).isTrue();

            long index = server.propose(Bytes.ofUtf8("only")).get(15, TimeUnit.SECONDS);

            assertThat(server.appliedIndex())
                    .as("a completed proposal implies its entry was applied first")
                    .isGreaterThanOrEqualTo(index);
            assertThat(stateMachine.applied()).containsExactly("only");
        }
    }
}
