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
import dev.flotilla.kv.Command;
import dev.flotilla.kv.CommandCodec;
import dev.flotilla.kv.KvRequest;
import dev.flotilla.kv.KvStateMachine;
import dev.flotilla.storage.FsyncPolicy;
import dev.flotilla.storage.StorageConfig;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SnapshotLifecycleTest {

    private static final NodeId N1 = NodeId.of("n1");
    private static final Duration PATIENCE = Duration.ofSeconds(30);
    private static final int WRITES = 400;
    private static final int ENTRIES_BETWEEN_SNAPSHOTS = 50;

    @TempDir
    Path directory;

    private RaftServer start(KvStateMachine stateMachine) {
        ServerConfig config = ServerConfig.defaults()
                .withTickInterval(Duration.ofMillis(5))
                .withEventQueueCapacity(4096)
                .withSnapshotPolicy(SnapshotPolicy.defaults()
                        .withEntriesBetweenSnapshots(ENTRIES_BETWEEN_SNAPSHOTS)
                        .withBytesBetweenSnapshots(1L << 30));
        return RaftServer.start(
                config,
                RaftConfig.defaults(N1),
                ClusterConfig.ofVoters(N1),
                StorageConfig.of(directory).withFsyncPolicy(FsyncPolicy.BATCHED),
                stateMachine,
                MessageSink.discarding());
    }

    private static Bytes put(int number) {
        return CommandCodec.encode(
                KvRequest.anonymous(new Command.Put(Bytes.ofUtf8("key-" + number), Bytes.ofUtf8("value-" + number))));
    }

    private static void fill(RaftServer server, int writes) throws Exception {
        for (int i = 0; i < writes; i++) {
            server.execute(put(i)).get(PATIENCE.toSeconds(), TimeUnit.SECONDS);
        }
    }

    private static boolean awaitCondition(Duration timeout, BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(5);
        }
        return condition.getAsBoolean();
    }

    @Test
    @DisplayName("writing past the policy takes a snapshot and the log stops starting at index 1")
    void snapshotsBoundTheLog() throws Exception {
        try (RaftServer server = start(new KvStateMachine())) {
            assertThat(server.awaitLeadership(PATIENCE)).isTrue();

            fill(server, WRITES);

            assertThat(awaitCondition(PATIENCE, () -> server.snapshotsTaken() > 0 && server.firstLogIndex() > 1))
                    .as(
                            "after %d writes with a snapshot every %d entries the log must have been compacted",
                            WRITES, ENTRIES_BETWEEN_SNAPSHOTS)
                    .isTrue();
            assertThat(server.logCompactions()).isPositive();
        }
    }

    @Test
    @DisplayName("a restart rebuilds the state from the snapshot plus the entries after it")
    void aRestartRebuildsFromTheSnapshot() throws Exception {
        try (RaftServer server = start(new KvStateMachine())) {
            assertThat(server.awaitLeadership(PATIENCE)).isTrue();
            fill(server, WRITES);
            assertThat(awaitCondition(PATIENCE, () -> server.snapshotsTaken() > 0 && server.firstLogIndex() > 1))
                    .isTrue();
        }

        KvStateMachine rebuilt = new KvStateMachine();
        try (RaftServer restarted = start(rebuilt)) {
            assertThat(restarted.awaitLeadership(PATIENCE)).isTrue();

            assertThat(restarted.firstLogIndex())
                    .as("the compacted prefix must still be gone after a restart")
                    .isGreaterThan(1);
            assertThat(rebuilt.size())
                    .as("every key written before the snapshot has to come back from it")
                    .isEqualTo(WRITES);
            for (int i = 0; i < WRITES; i++) {
                assertThat(rebuilt.get(Bytes.ofUtf8("key-" + i))).contains(Bytes.ofUtf8("value-" + i));
            }
        }
    }

    @Test
    @DisplayName("the apply loop stops only to copy the state, not to serialize it")
    void theApplyPauseIsShorterThanTheSerialization() throws Exception {
        try (RaftServer server = start(new KvStateMachine())) {
            assertThat(server.awaitLeadership(PATIENCE)).isTrue();
            fill(server, WRITES);
            assertThat(awaitCondition(PATIENCE, () -> server.snapshotsTaken() > 0))
                    .isTrue();

            Duration pause = server.longestSnapshotApplyPause();
            assertThat(pause.isNegative()).isFalse();
            assertThat(pause)
                    .as("a snapshot that blocks the apply loop for this long delays every heartbeat behind it")
                    .isLessThan(Duration.ofMillis(250));
        }
    }

    @Test
    @DisplayName("writes keep being answered while a snapshot is being written")
    void writesContinueDuringASnapshot() throws Exception {
        try (RaftServer server = start(new KvStateMachine())) {
            assertThat(server.awaitLeadership(PATIENCE)).isTrue();

            fill(server, WRITES * 2);

            assertThat(server.snapshotsTaken()).isPositive();
            assertThat(server.appliedIndex()).isGreaterThanOrEqualTo(WRITES * 2L);
            assertThat(server.failure()).isEmpty();
        }
    }

    @Test
    @DisplayName("only the retained snapshots are kept on disk")
    void oldSnapshotFilesAreCleanedUp() throws Exception {
        try (RaftServer server = start(new KvStateMachine())) {
            assertThat(server.awaitLeadership(PATIENCE)).isTrue();
            fill(server, WRITES * 2);
            assertThat(awaitCondition(PATIENCE, () -> server.snapshotsTaken() >= 3))
                    .isTrue();
        }

        try (var files = java.nio.file.Files.list(directory)) {
            assertThat(files.filter(file -> file.getFileName().toString().endsWith(".snap"))
                            .toList())
                    .hasSizeLessThanOrEqualTo(2);
        }
    }
}
