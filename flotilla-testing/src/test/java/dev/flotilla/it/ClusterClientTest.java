/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.it;

import static org.assertj.core.api.Assertions.assertThat;

import dev.flotilla.client.ClientConfig;
import dev.flotilla.client.FlotillaClient;
import dev.flotilla.client.HistoryRecorder;
import dev.flotilla.core.Bytes;
import dev.flotilla.core.ClusterConfig;
import dev.flotilla.core.NodeId;
import dev.flotilla.core.RaftConfig;
import dev.flotilla.kv.KvStateMachine;
import dev.flotilla.server.FlotillaNode;
import dev.flotilla.server.ServerConfig;
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
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClusterClientTest {

    private static final List<NodeId> IDS = List.of(NodeId.of("n1"), NodeId.of("n2"), NodeId.of("n3"));
    private static final Duration TICK = Duration.ofMillis(50);
    private static final Duration PATIENCE = Duration.ofSeconds(30);
    private static final ClientConfig CLIENT = ClientConfig.defaults()
            .withAttemptDeadline(Duration.ofSeconds(1))
            .withMaxAttempts(60)
            .withBackoff(Duration.ofMillis(20), Duration.ofMillis(400));

    @TempDir
    Path directory;

    private final Map<NodeId, InetSocketAddress> addresses = new ConcurrentHashMap<>();
    private final Map<NodeId, FlotillaNode> running = new LinkedHashMap<>();

    private void startAll() {
        for (NodeId id : IDS) {
            FlotillaNode node = FlotillaNode.start(
                    ServerConfig.defaults().withTickInterval(TICK),
                    RaftConfig.defaults(id),
                    ClusterConfig.ofVoters(IDS),
                    StorageConfig.of(directory.resolve(id.value())).withFsyncPolicy(FsyncPolicy.NEVER),
                    new KvStateMachine(),
                    TransportConfig.defaults().withDeliverDeadline(Duration.ofMillis(100)),
                    peer -> Optional.ofNullable(addresses.get(peer)),
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            addresses.put(id, node.address());
            running.put(id, node);
        }
    }

    @AfterEach
    void stopAll() {
        running.values().forEach(FlotillaNode::close);
        running.clear();
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
        throw new AssertionError("No leader emerged within " + PATIENCE);
    }

    private List<InetSocketAddress> seedsFollowersFirst(NodeId leader) {
        List<InetSocketAddress> seeds = new ArrayList<>();
        for (NodeId id : IDS) {
            if (!id.equals(leader)) {
                seeds.add(Objects.requireNonNull(addresses.get(id)));
            }
        }
        seeds.add(Objects.requireNonNull(addresses.get(leader)));
        return seeds;
    }

    private static Bytes b(String text) {
        return Bytes.ofUtf8(text);
    }

    @Test
    @DisplayName("a client that starts at a follower finds the leader and reads its own writes")
    void theClientFindsTheLeaderFromAFollower() throws Exception {
        startAll();
        NodeId leader = awaitLeader();

        try (FlotillaClient client = FlotillaClient.connect(seedsFollowersFirst(leader), CLIENT)) {
            assertThat(client.put(b("greeting"), b("hello"))).isEmpty();
            assertThat(client.get(b("greeting"))).contains(b("hello"));
            assertThat(client.compareAndSwap(b("greeting"), b("hello"), b("world")))
                    .isTrue();
            assertThat(client.get(b("greeting"))).contains(b("world"));
        }
    }

    @Test
    @DisplayName("increments keep counting exactly once while the leader is killed underneath the client")
    void incrementsSurviveALeaderFailoverExactlyOnce() throws Exception {
        startAll();
        NodeId first = awaitLeader();
        HistoryRecorder history = new HistoryRecorder(System::nanoTime);
        int increments = 30;

        try (FlotillaClient client = FlotillaClient.connect(seedsFollowersFirst(first), CLIENT, history)) {
            client.put(b("counter"), b("0"));
            for (int i = 0; i < increments; i++) {
                if (i == increments / 3) {
                    FlotillaNode killed = running.remove(first);
                    if (killed != null) {
                        killed.close();
                    }
                }
                Bytes current = client.get(b("counter")).orElseThrow();
                Bytes next = b(Integer.toString(Integer.parseInt(current.toUtf8()) + 1));

                assertThat(client.compareAndSwap(b("counter"), current, next))
                        .as("increment %d: a lone client can only lose a swap if a retry ran it twice", i)
                        .isTrue();
            }

            assertThat(client.get(b("counter"))).contains(b(Integer.toString(increments)));
        }
        assertThat(history.events())
                .as("every operation that was invoked also completed")
                .extracting(HistoryRecorder.Event::type)
                .containsOnly(HistoryRecorder.Type.INVOKE, HistoryRecorder.Type.OK);
    }
}
