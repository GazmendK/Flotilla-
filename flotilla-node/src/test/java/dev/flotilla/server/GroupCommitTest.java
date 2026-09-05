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
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GroupCommitTest {

    private static final NodeId N1 = NodeId.of("n1");
    private static final Duration PATIENCE = Duration.ofSeconds(30);
    private static final int LOAD = 2000;

    @TempDir
    Path directory;

    private final RecordingStateMachine stateMachine = new RecordingStateMachine();

    private RaftServer start(ServerConfig config) {
        return RaftServer.start(
                config,
                RaftConfig.defaults(N1),
                ClusterConfig.ofVoters(N1),
                StorageConfig.of(directory).withFsyncPolicy(FsyncPolicy.ALWAYS),
                stateMachine,
                MessageSink.discarding());
    }

    @Test
    @DisplayName("a burst of proposals costs far fewer fsyncs than it has entries")
    void concurrentProposalsShareAnFsync() throws Exception {
        ServerConfig config =
                ServerConfig.defaults().withTickInterval(Duration.ofMillis(5)).withEventQueueCapacity(LOAD * 2);

        try (RaftServer server = start(config)) {
            assertThat(server.awaitLeadership(PATIENCE)).isTrue();
            long syncsBefore = server.syncs();

            List<CompletableFuture<Long>> submitted = new ArrayList<>();
            for (int i = 0; i < LOAD; i++) {
                submitted.add(server.propose(Bytes.ofUtf8("v" + i)));
            }
            CompletableFuture.allOf(submitted.toArray(CompletableFuture[]::new))
                    .get(PATIENCE.toSeconds(), TimeUnit.SECONDS);

            long syncs = server.syncs() - syncsBefore;
            assertThat(server.persistedEntries()).isGreaterThanOrEqualTo(LOAD);
            assertThat(server.largestBatch())
                    .as("if every event were handled alone there would be no group commit at all")
                    .isGreaterThan(1);
            assertThat(syncs)
                    .as("%d entries needed %d fsyncs; batching is what makes that ratio possible", LOAD, syncs)
                    .isLessThan(LOAD / 4);
        }
    }

    @Test
    @DisplayName("a batch size of one turns group commit off, which is what makes the comparison honest")
    void withoutBatchingEveryEntryPaysForItself() throws Exception {
        ServerConfig config = ServerConfig.defaults()
                .withTickInterval(Duration.ofMillis(50))
                .withMaxBatchSize(1)
                .withEventQueueCapacity(512);

        int load = 200;
        try (RaftServer server = start(config)) {
            assertThat(server.awaitLeadership(PATIENCE)).isTrue();

            List<CompletableFuture<Long>> submitted = new ArrayList<>();
            for (int i = 0; i < load; i++) {
                submitted.add(server.propose(Bytes.ofUtf8("v" + i)));
            }
            CompletableFuture.allOf(submitted.toArray(CompletableFuture[]::new))
                    .get(PATIENCE.toSeconds(), TimeUnit.SECONDS);

            assertThat(server.largestBatch()).isEqualTo(1);
            assertThat(server.syncs())
                    .as("one event per iteration means one fsync per entry")
                    .isGreaterThanOrEqualTo(load);
        }
    }
}
