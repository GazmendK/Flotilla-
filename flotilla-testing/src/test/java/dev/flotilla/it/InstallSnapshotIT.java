/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.it;

import static org.assertj.core.api.Assertions.assertThat;

import dev.flotilla.core.Bytes;
import dev.flotilla.core.ClusterConfig;
import dev.flotilla.core.NodeId;
import dev.flotilla.core.RaftConfig;
import dev.flotilla.kv.Command;
import dev.flotilla.kv.CommandCodec;
import dev.flotilla.kv.KvRequest;
import dev.flotilla.kv.KvStateMachine;
import dev.flotilla.server.FlotillaNode;
import dev.flotilla.server.ServerConfig;
import dev.flotilla.server.SnapshotPolicy;
import dev.flotilla.storage.FsyncPolicy;
import dev.flotilla.storage.StorageConfig;
import dev.flotilla.transport.TransportConfig;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InstallSnapshotIT {

    private static final List<NodeId> IDS = List.of(NodeId.of("n1"), NodeId.of("n2"), NodeId.of("n3"));
    private static final Duration TICK = Duration.ofMillis(20);
    private static final Duration PATIENCE = Duration.ofSeconds(60);
    private static final int WRITES = 600;
    private static final int VALUE_BYTES = 2048;
    private static final int CHUNK_BYTES = 16 * 1024;
    private static final int MAX_MESSAGE_BYTES = 128 * 1024;

    @TempDir
    Path directory;

    private final Map<NodeId, InetSocketAddress> addresses = new ConcurrentHashMap<>();
    private final Map<NodeId, FlotillaNode> running = new LinkedHashMap<>();
    private final Map<NodeId, KvStateMachine> stores = new LinkedHashMap<>();

    private FlotillaNode start(NodeId id) {
        KvStateMachine store = new KvStateMachine();
        FlotillaNode node = FlotillaNode.start(
                ServerConfig.defaults()
                        .withTickInterval(TICK)
                        .withEventQueueCapacity(8192)
                        .withSnapshotPolicy(SnapshotPolicy.defaults()
                                .withEntriesBetweenSnapshots(100)
                                .withBytesBetweenSnapshots(1L << 30)),
                RaftConfig.builder(id).maxAppendBytes(32 * 1024).build(),
                ClusterConfig.ofVoters(IDS),
                StorageConfig.of(directory.resolve(id.value())).withFsyncPolicy(FsyncPolicy.NEVER),
                store,
                TransportConfig.defaults()
                        .withDeliverDeadline(Duration.ofMillis(100))
                        .withSnapshotChunkBytes(CHUNK_BYTES)
                        .withMaxMessageBytes(MAX_MESSAGE_BYTES),
                peer -> Optional.ofNullable(addresses.get(peer)),
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        addresses.put(id, node.address());
        running.put(id, node);
        stores.put(id, store);
        return node;
    }

    @AfterEach
    void stopAll() {
        new ArrayList<>(running.keySet()).forEach(id -> {
            FlotillaNode node = running.remove(id);
            if (node != null) {
                node.close();
            }
        });
    }

    private FlotillaNode node(NodeId id) {
        FlotillaNode node = running.get(id);
        if (node == null) {
            throw new AssertionError(id + " is not running");
        }
        return node;
    }

    private KvStateMachine store(NodeId id) {
        KvStateMachine store = stores.get(id);
        if (store == null) {
            throw new AssertionError(id + " was never started");
        }
        return store;
    }

    private NodeId awaitLeader() throws InterruptedException {
        long deadline = System.nanoTime() + PATIENCE.toNanos();
        while (System.nanoTime() < deadline) {
            for (Map.Entry<NodeId, FlotillaNode> entry : running.entrySet()) {
                if (entry.getValue().server().isLeader()) {
                    return entry.getKey();
                }
            }
            Thread.sleep(10);
        }
        throw new AssertionError("No leader emerged among " + running.keySet());
    }

    private static boolean await(Duration timeout, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(10);
        }
        return condition.getAsBoolean();
    }

    private static Bytes put(int number) {
        return CommandCodec.encode(KvRequest.anonymous(new Command.Put(
                Bytes.ofUtf8("key-" + number),
                Bytes.ofUtf8(Integer.toString(number).repeat(VALUE_BYTES / 4)))));
    }

    @Test
    @DisplayName("a node that was down while the leader compacted catches up over a streamed snapshot")
    void aStoppedNodeCatchesUpFromASnapshot() throws Exception {
        IDS.forEach(this::start);
        NodeId leader = awaitLeader();
        NodeId lagging =
                IDS.stream().filter(id -> !id.equals(leader)).findFirst().orElseThrow();

        node(lagging).close();
        running.remove(lagging);

        for (int i = 0; i < WRITES; i++) {
            node(leader).server().propose(put(i)).get(PATIENCE.toSeconds(), TimeUnit.SECONDS);
        }
        assertThat(await(PATIENCE, () -> node(leader).server().firstLogIndex() > 1))
                .as("the leader has to compact before a snapshot transfer is the only way to catch up")
                .isTrue();
        long compactedThrough = node(leader).server().firstLogIndex() - 1;

        start(lagging);

        assertThat(await(
                        PATIENCE,
                        () -> node(lagging).server().appliedIndex()
                                >= node(leader).server().appliedIndex()))
                .as("the restarted node has to catch up past the compacted prefix at index %d", compactedThrough)
                .isTrue();
        assertThat(node(lagging).server().snapshotRestores())
                .as("it can only have got there through a snapshot")
                .isPositive();
        assertThat(node(leader).transport().snapshotChunksSent())
                .as("and it has to have arrived in more than one chunk over the wire")
                .isGreaterThan(1);

        KvStateMachine caughtUp = store(lagging);
        assertThat(caughtUp.size()).isEqualTo(WRITES);
        for (int i = 0; i < WRITES; i += 97) {
            assertThat(caughtUp.get(Bytes.ofUtf8("key-" + i))).isPresent();
        }
    }
}
