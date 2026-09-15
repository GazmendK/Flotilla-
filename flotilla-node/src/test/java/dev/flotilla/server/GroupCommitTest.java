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
    private static final Duration PATIENCE = Duration.ofSeconds(60);

    @TempDir
    Path directory;

    private final RecordingStateMachine stateMachine = new RecordingStateMachine();

    private RaftServer start(ServerConfig config, FsyncPolicy policy) {
        return RaftServer.start(
                config,
                RaftConfig.defaults(N1),
                ClusterConfig.ofVoters(N1),
                StorageConfig.of(directory).withFsyncPolicy(policy),
                stateMachine,
                MessageSink.discarding());
    }

    private static void proposeAndAwait(RaftServer server, int count) throws Exception {
        List<CompletableFuture<Long>> submitted = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            submitted.add(server.propose(Bytes.ofUtf8("v" + i)));
        }
        CompletableFuture.allOf(submitted.toArray(CompletableFuture[]::new))
                .get(PATIENCE.toSeconds(), TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("a burst of proposals costs far fewer physical fsyncs than it has entries")
    void concurrentProposalsShareAnFsync() throws Exception {
        int load = 2000;
        ServerConfig config =
                ServerConfig.defaults().withTickInterval(Duration.ofMillis(5)).withEventQueueCapacity(load * 2);

        try (RaftServer server = start(config, StorageConfig.of(directory).fsyncPolicy())) {
            assertThat(server.awaitLeadership(PATIENCE)).isTrue();
            long before = server.forcedLogSyncs();

            proposeAndAwait(server, load);

            long forced = server.forcedLogSyncs() - before;
            assertThat(server.largestBatch())
                    .as("if every event were handled alone there would be no group commit at all")
                    .isGreaterThan(1);
            assertThat(forced)
                    .as(
                            "%d entries were forced to disk %d times; batching is what makes that ratio possible",
                            load, forced)
                    .isLessThan(load / 4);
        }
    }

    @Test
    @DisplayName("a batch size of one turns group commit off, which is what makes the comparison honest")
    void withoutBatchingEveryEntryPaysForItself() throws Exception {
        int load = 200;
        ServerConfig config = ServerConfig.defaults()
                .withTickInterval(Duration.ofMillis(50))
                .withMaxBatchSize(1)
                .withEventQueueCapacity(512);

        try (RaftServer server = start(config, FsyncPolicy.BATCHED)) {
            assertThat(server.awaitLeadership(PATIENCE)).isTrue();
            long before = server.forcedLogSyncs();

            proposeAndAwait(server, load);

            assertThat(server.largestBatch()).isEqualTo(1);
            assertThat(server.forcedLogSyncs() - before)
                    .as("one event per iteration means one fsync per entry")
                    .isGreaterThanOrEqualTo(load);
        }
    }

    @Test
    @DisplayName("forcing on every append defeats batching entirely, which is why it is not the default")
    void syncingEveryAppendDefeatsGroupCommit() throws Exception {
        int load = 200;
        ServerConfig config =
                ServerConfig.defaults().withTickInterval(Duration.ofMillis(5)).withEventQueueCapacity(load * 2);

        try (RaftServer server = start(config, FsyncPolicy.ALWAYS)) {
            assertThat(server.awaitLeadership(PATIENCE)).isTrue();
            long before = server.forcedLogSyncs();

            proposeAndAwait(server, load);

            assertThat(server.forcedLogSyncs() - before)
                    .as("each proposal appends, and each append forces, before the event loop can batch anything")
                    .isGreaterThanOrEqualTo(load);
        }
    }
}
