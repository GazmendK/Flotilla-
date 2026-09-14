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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ThreeNodeClusterTest {

    private static final List<NodeId> IDS = List.of(NodeId.of("n1"), NodeId.of("n2"), NodeId.of("n3"));
    private static final Duration TICK = Duration.ofMillis(20);
    private static final Duration PATIENCE = Duration.ofSeconds(30);

    @TempDir
    Path directory;

    private final Map<NodeId, InetSocketAddress> addresses = new ConcurrentHashMap<>();
    private final Map<NodeId, FlotillaNode> running = new LinkedHashMap<>();
    private final Map<NodeId, KvStateMachine> stores = new LinkedHashMap<>();

    private FlotillaNode start(NodeId id) {
        KvStateMachine store = new KvStateMachine();
        FlotillaNode node = FlotillaNode.start(
                ServerConfig.defaults().withTickInterval(TICK),
                RaftConfig.defaults(id),
                ClusterConfig.ofVoters(IDS),
                StorageConfig.of(directory.resolve(id.value())).withFsyncPolicy(FsyncPolicy.NEVER),
                store,
                TransportConfig.defaults().withDeliverDeadline(Duration.ofMillis(100)),
                peer -> Optional.ofNullable(addresses.get(peer)),
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        addresses.put(id, node.address());
        running.put(id, node);
        stores.put(id, store);
        return node;
    }

    private void startAll() {
        IDS.forEach(this::start);
    }

    private void stop(NodeId id) {
        FlotillaNode node = running.remove(id);
        if (node != null) {
            node.close();
        }
    }

    @AfterEach
    void stopAll() {
        new ArrayList<>(running.keySet()).forEach(this::stop);
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
        throw new AssertionError("No leader emerged among " + running.keySet() + " within " + PATIENCE);
    }

    private long put(NodeId leader, String key, String value) throws Exception {
        return node(leader)
                .server()
                .propose(CommandCodec.encode(KvRequest.anonymous(Command.put(key, value))))
                .get(PATIENCE.toSeconds(), TimeUnit.SECONDS);
    }

    private void awaitAppliedEverywhere(long index) {
        for (Map.Entry<NodeId, FlotillaNode> entry : running.entrySet()) {
            assertThat(entry.getValue().server().awaitApplied(index, PATIENCE))
                    .as("%s applied index %d", entry.getKey(), index)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("three processes' worth of nodes elect exactly one leader over real sockets")
    void aLeaderIsElectedOverTheNetwork() throws Exception {
        startAll();

        NodeId leader = awaitLeader();

        long leaders = running.values().stream()
                .filter(node -> node.server().isLeader())
                .count();
        assertThat(leaders).isEqualTo(1);
        assertThat(node(leader).transport().delivered())
                .as("the election was won by messages that actually crossed the network")
                .isPositive();
    }

    @Test
    @DisplayName("a write through the leader reaches every replica's state machine")
    void writesReachEveryReplica() throws Exception {
        startAll();
        NodeId leader = awaitLeader();

        long index = put(leader, "greeting", "hello");
        awaitAppliedEverywhere(index);

        for (Map.Entry<NodeId, KvStateMachine> store : stores.entrySet()) {
            assertThat(store.getValue().get(Bytes.ofUtf8("greeting")))
                    .as("replica %s", store.getKey())
                    .contains(Bytes.ofUtf8("hello"));
        }
    }

    @Test
    @DisplayName("when the leader dies a new one takes over and no acknowledged write is lost")
    void failoverLosesNothingThatWasAcknowledged() throws Exception {
        startAll();
        NodeId first = awaitLeader();
        long last = 0;
        for (int i = 0; i < 20; i++) {
            last = put(first, "k" + i, "v" + i);
        }

        stop(first);
        NodeId second = awaitLeader();

        assertThat(second).isNotEqualTo(first);
        awaitAppliedEverywhere(last);
        for (NodeId survivor : running.keySet()) {
            for (int i = 0; i < 20; i++) {
                assertThat(store(survivor).get(Bytes.ofUtf8("k" + i)))
                        .as("%s still holds acknowledged write k%d", survivor, i)
                        .contains(Bytes.ofUtf8("v" + i));
            }
        }
        long afterFailover = put(second, "after", "failover");
        assertThat(afterFailover).isGreaterThan(last);
    }

    @Test
    @DisplayName("a follower restarted on a new port is found again and catches up")
    void aRestartedFollowerCatchesUp() throws Exception {
        startAll();
        NodeId leader = awaitLeader();
        NodeId follower =
                IDS.stream().filter(id -> !id.equals(leader)).findFirst().orElseThrow();
        InetSocketAddress before = addresses.get(follower);

        stop(follower);
        long last = 0;
        for (int i = 0; i < 10; i++) {
            last = put(leader, "while-away-" + i, "x");
        }
        start(follower);

        assertThat(addresses.get(follower)).isNotEqualTo(before);
        awaitAppliedEverywhere(last);
        assertThat(store(follower).get(Bytes.ofUtf8("while-away-9"))).contains(Bytes.ofUtf8("x"));
    }
}
