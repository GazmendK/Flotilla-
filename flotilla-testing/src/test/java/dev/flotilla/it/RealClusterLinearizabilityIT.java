/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.it;

import static org.assertj.core.api.Assertions.assertThat;

import dev.flotilla.client.ClientConfig;
import dev.flotilla.client.FlotillaClient;
import dev.flotilla.client.FlotillaClientException;
import dev.flotilla.client.HistoryRecorder;
import dev.flotilla.core.Bytes;
import dev.flotilla.core.ClusterConfig;
import dev.flotilla.core.NodeId;
import dev.flotilla.core.RaftConfig;
import dev.flotilla.kv.KvStateMachine;
import dev.flotilla.linearizability.CheckResult;
import dev.flotilla.linearizability.CounterexampleRenderer;
import dev.flotilla.linearizability.KvModel;
import dev.flotilla.linearizability.LinearizabilityChecker;
import dev.flotilla.linearizability.Operation;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RealClusterLinearizabilityIT {

    private static final List<NodeId> IDS = List.of(NodeId.of("n1"), NodeId.of("n2"), NodeId.of("n3"));
    private static final Duration TICK = Duration.ofMillis(20);
    private static final Duration PATIENCE = Duration.ofSeconds(30);
    private static final int CLIENTS = 4;
    private static final int FOLLOWER_READERS = 2;
    private static final int KEYS = 6;
    private static final KvModel MODEL = new KvModel();
    private static final ClientConfig CLIENT = ClientConfig.defaults()
            .withAttemptDeadline(Duration.ofMillis(500))
            .withMaxAttempts(8)
            .withBackoff(Duration.ofMillis(20), Duration.ofMillis(200));

    @TempDir
    Path directory;

    private final Map<NodeId, InetSocketAddress> addresses = new ConcurrentHashMap<>();
    private final Map<NodeId, FlotillaNode> running = new LinkedHashMap<>();

    private synchronized void start(NodeId id) {
        FlotillaNode node = FlotillaNode.start(
                ServerConfig.defaults().withTickInterval(TICK),
                RaftConfig.defaults(id),
                ClusterConfig.ofVoters(IDS),
                StorageConfig.of(directory.resolve(id.value())).withFsyncPolicy(FsyncPolicy.BATCHED),
                new KvStateMachine(),
                TransportConfig.defaults().withDeliverDeadline(Duration.ofMillis(100)),
                peer -> Optional.ofNullable(addresses.get(peer)),
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        addresses.put(id, node.address());
        running.put(id, node);
    }

    private synchronized void kill(NodeId id) {
        FlotillaNode node = running.remove(id);
        if (node != null) {
            node.close();
        }
    }

    @AfterEach
    synchronized void stopAll() {
        running.values().forEach(FlotillaNode::close);
        running.clear();
    }

    private NodeId awaitLeader() throws InterruptedException {
        long deadline = System.nanoTime() + PATIENCE.toNanos();
        while (System.nanoTime() < deadline) {
            synchronized (this) {
                for (Map.Entry<NodeId, FlotillaNode> entry : running.entrySet()) {
                    if (entry.getValue().server().isLeader()) {
                        return entry.getKey();
                    }
                }
            }
            Thread.sleep(10);
        }
        throw new AssertionError("No leader emerged within " + PATIENCE);
    }

    private static Bytes b(String text) {
        return Bytes.ofUtf8(text);
    }

    private static void workload(FlotillaClient client, int process, AtomicBoolean stop, long seed) {
        SplittableRandom random = new SplittableRandom(seed);
        Map<String, String> lastSeen = new HashMap<>();
        int counter = 0;
        while (!stop.get()) {
            String key = "key-" + random.nextInt(KEYS);
            String fresh = "p" + process + "-" + ++counter;
            try {
                int kind = random.nextInt(10);
                if (kind < 5) {
                    client.get(b(key))
                            .ifPresentOrElse(value -> lastSeen.put(key, value.toUtf8()), () -> lastSeen.remove(key));
                } else if (kind < 8) {
                    client.put(b(key), b(fresh));
                    lastSeen.put(key, fresh);
                } else {
                    String expected = lastSeen.get(key);
                    if (client.compareAndSwap(b(key), expected == null ? null : b(expected), b(fresh))) {
                        lastSeen.put(key, fresh);
                    }
                }
            } catch (FlotillaClientException unavailable) {
                lastSeen.clear();
            }
        }
    }

    private static void readOnly(FlotillaClient client, AtomicBoolean stop, long seed) {
        SplittableRandom random = new SplittableRandom(seed);
        while (!stop.get()) {
            try {
                client.get(b("key-" + random.nextInt(KEYS)));
            } catch (FlotillaClientException unavailable) {
                Thread.onSpinWait();
            }
        }
    }

    @Test
    @DisplayName("concurrent clients against three real nodes stay linearizable while the leader is killed twice")
    void aRealClusterStaysLinearizableThroughLeaderFailures() throws Exception {
        IDS.forEach(this::start);
        NodeId firstLeader = awaitLeader();
        List<InetSocketAddress> seeds = List.copyOf(addresses.values());
        HistoryRecorder history = new HistoryRecorder(System::nanoTime);
        AtomicBoolean stop = new AtomicBoolean();
        CountDownLatch finished = new CountDownLatch(CLIENTS + FOLLOWER_READERS);
        List<FlotillaClient> clients = new ArrayList<>();

        for (int process = 0; process < CLIENTS; process++) {
            FlotillaClient client = FlotillaClient.connect(seeds, CLIENT, history);
            clients.add(client);
            int self = process;
            Thread.ofPlatform().name("client-" + process).start(() -> {
                try {
                    workload(client, self, stop, 20260916L + self);
                } finally {
                    finished.countDown();
                }
            });
        }

        List<InetSocketAddress> followersFirst = new ArrayList<>();
        IDS.stream()
                .filter(id -> !id.equals(firstLeader))
                .forEach(id -> followersFirst.add(Objects.requireNonNull(addresses.get(id))));
        for (int reader = 0; reader < FOLLOWER_READERS; reader++) {
            List<InetSocketAddress> pinned = new ArrayList<>(followersFirst.subList(reader, followersFirst.size()));
            pinned.addAll(followersFirst.subList(0, reader));
            FlotillaClient client = FlotillaClient.connect(pinned, CLIENT, history);
            clients.add(client);
            long seed = 7_000L + reader;
            Thread.ofPlatform().name("follower-reader-" + reader).start(() -> {
                try {
                    readOnly(client, stop, seed);
                } finally {
                    finished.countDown();
                }
            });
        }

        Thread.sleep(2_000);
        kill(firstLeader);
        Thread.sleep(2_000);
        start(firstLeader);
        NodeId secondLeader = awaitLeader();
        Thread.sleep(1_500);
        kill(secondLeader);
        Thread.sleep(2_000);
        start(secondLeader);
        Thread.sleep(1_500);
        KvHistories.keepGoingUntil(history, 150, 150, Duration.ofSeconds(30));
        stop.set(true);
        finished.await();
        clients.forEach(FlotillaClient::close);

        List<Operation<KvModel.Input, KvModel.Output>> operations = KvHistories.operations(history.events());
        long completedReads = KvHistories.completedReads(operations);
        long completedWrites = KvHistories.completedWrites(operations);
        long unknown = KvHistories.unknown(operations);

        CheckResult<KvModel.State, KvModel.Input, KvModel.Output> result =
                new LinearizabilityChecker().check(MODEL, operations, Duration.ofMinutes(2));
        System.out.println("REAL CLUSTER: " + completedReads + " reads, " + completedWrites + " writes, " + unknown
                + " unknown -> " + result);

        assertThat(completedReads)
                .as("too few reads completed for a pass to mean anything")
                .isGreaterThan(100);
        assertThat(completedWrites)
                .as("too few writes completed for a pass to mean anything")
                .isGreaterThan(100);
        assertThat(result.outcome())
                .as(
                        "%s%n%s",
                        result,
                        result.counterexample()
                                .map(counterexample -> CounterexampleRenderer.render(MODEL, counterexample))
                                .orElse(""))
                .isEqualTo(CheckResult.Outcome.LINEARIZABLE);
    }
}
