/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.it;

import static org.assertj.core.api.Assertions.assertThat;

import dev.flotilla.client.ClientConfig;
import dev.flotilla.client.FlotillaAdmin;
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
import dev.flotilla.transport.ClusterStatus;
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
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScaleClusterIT {

    private static final NodeId N1 = NodeId.of("n1");
    private static final NodeId N2 = NodeId.of("n2");
    private static final NodeId N3 = NodeId.of("n3");
    private static final NodeId N4 = NodeId.of("n4");
    private static final NodeId N5 = NodeId.of("n5");
    private static final ClusterConfig INITIAL = ClusterConfig.ofVoters(N1, N2, N3);
    private static final Duration TICK = Duration.ofMillis(50);
    private static final Duration PATIENCE = Duration.ofSeconds(30);
    private static final int CLIENTS = 4;
    private static final int KEYS = 6;
    private static final KvModel MODEL = new KvModel();
    private static final ClientConfig CLIENT = ClientConfig.defaults();
    private static final ClientConfig ADMIN = CLIENT.withAttemptDeadline(Duration.ofSeconds(5));

    @TempDir
    Path directory;

    private final Map<NodeId, InetSocketAddress> addresses = new ConcurrentHashMap<>();
    private final Map<NodeId, FlotillaNode> running = new LinkedHashMap<>();
    private final ConcurrentLinkedQueue<String> clientErrors = new ConcurrentLinkedQueue<>();

    private synchronized void start(NodeId id) {
        FlotillaNode node = FlotillaNode.start(
                ServerConfig.defaults().withTickInterval(TICK),
                RaftConfig.defaults(id),
                INITIAL,
                StorageConfig.of(directory.resolve(id.value())).withFsyncPolicy(FsyncPolicy.BATCHED),
                new KvStateMachine(),
                TransportConfig.defaults().withDeliverDeadline(Duration.ofMillis(100)),
                peer -> Optional.ofNullable(addresses.get(peer)),
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        addresses.put(id, node.address());
        running.put(id, node);
    }

    private synchronized void stop(NodeId id) {
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

    private final long began = System.nanoTime();

    private String elapsed() {
        return "t+" + (System.nanoTime() - began) / 1_000_000 + "ms";
    }

    private static Bytes b(String text) {
        return Bytes.ofUtf8(text);
    }

    private void workload(FlotillaClient client, int process, AtomicBoolean stop) {
        SplittableRandom random = new SplittableRandom(20260921L + process);
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
            } catch (FlotillaClientException failed) {
                clientErrors.add(elapsed() + " p" + process + ": " + failed.getMessage() + " / last failure: "
                        + (failed.getCause() == null
                                ? "none"
                                : failed.getCause().getMessage()));
                lastSeen.clear();
            }
        }
    }

    private void stopOnceSomeoneElseLeads(FlotillaAdmin admin, NodeId removed) throws InterruptedException {
        long deadline = System.nanoTime() + PATIENCE.toNanos();
        while (admin.describe()
                .knownLeader()
                .filter(leader -> !leader.equals(removed))
                .isEmpty()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError(removed + " still leads " + PATIENCE + " after its removal");
            }
            Thread.sleep(10);
        }
        stop(removed);
    }

    private static NodeId handOver(FlotillaAdmin admin, NodeId target) {
        FlotillaClientException last = null;
        for (int attempt = 0; attempt < 5; attempt++) {
            try {
                return admin.transferLeadership(target);
            } catch (FlotillaClientException notInTime) {
                last = notInTime;
                if (admin.describe().knownLeader().filter(target::equals).isPresent()) {
                    return target;
                }
            }
        }
        throw new AssertionError("leadership never reached " + target, last);
    }

    private static ClusterConfig promote(FlotillaAdmin admin, NodeId learner) {
        FlotillaAdmin.CatchUpReport report = admin.awaitCaughtUp(learner, PATIENCE);
        assertThat(report.caughtUp())
                .as("%s never caught up: %s", learner, report.status())
                .isTrue();
        return admin.promote(learner);
    }

    @Test
    @DisplayName("a cluster grows from three voters to five and shrinks back to three under load, "
            + "and no client sees an error or a non-linearizable result")
    void aClusterGrowsAndShrinksUnderLoad() throws Exception {
        List.of(N1, N2, N3, N4, N5).forEach(this::start);
        List<InetSocketAddress> seeds = List.of(addresses.get(N1), addresses.get(N2), addresses.get(N3));
        HistoryRecorder history = new HistoryRecorder(System::nanoTime);
        AtomicBoolean stop = new AtomicBoolean();
        CountDownLatch finished = new CountDownLatch(CLIENTS);
        List<FlotillaClient> clients = new ArrayList<>();
        for (int process = 0; process < CLIENTS; process++) {
            FlotillaClient client = FlotillaClient.connect(seeds, CLIENT, history);
            clients.add(client);
            int self = process;
            Thread.ofPlatform().name("client-" + process).start(() -> {
                try {
                    workload(client, self, stop);
                } finally {
                    finished.countDown();
                }
            });
        }

        List<String> steps = new ArrayList<>();
        try (FlotillaAdmin admin = FlotillaAdmin.connect(seeds, ADMIN)) {
            Thread.sleep(1_000);
            admin.addLearner(N4);
            steps.add(elapsed() + " added n4 as a learner: " + admin.describe().configuration());
            steps.add(elapsed() + " promoted n4: " + promote(admin, N4));
            admin.addLearner(N5);
            steps.add(elapsed() + " promoted n5: " + promote(admin, N5));
            Thread.sleep(1_000);

            if (!admin.describe().knownLeader().orElseThrow().equals(N1)) {
                steps.add(elapsed() + " handed leadership to " + handOver(admin, N1));
            }
            Thread.sleep(1_000);

            steps.add(elapsed() + " n1 removed itself while leading: " + admin.remove(N1));
            stopOnceSomeoneElseLeads(admin, N1);
            Thread.sleep(500);
            steps.add(elapsed() + " removed n2: " + admin.remove(N2));
            stopOnceSomeoneElseLeads(admin, N2);
            Thread.sleep(1_500);

            ClusterStatus last = admin.describe();
            steps.add(elapsed() + " final: " + last.configuration() + " led by " + last.leader());
            assertThat(last.configuration()).isEqualTo(ClusterConfig.ofVoters(N3, N4, N5));
            assertThat(last.configurationCommitted()).isTrue();
            KvHistories.keepGoingUntil(history, 150, 150, Duration.ofSeconds(30));
        } finally {
            stop.set(true);
            finished.await();
            clients.forEach(FlotillaClient::close);
        }

        List<Operation<KvModel.Input, KvModel.Output>> operations = KvHistories.operations(history.events());
        long completedReads = KvHistories.completedReads(operations);
        long completedWrites = KvHistories.completedWrites(operations);
        long unknown = KvHistories.unknown(operations);
        CheckResult<KvModel.State, KvModel.Input, KvModel.Output> result =
                new LinearizabilityChecker().check(MODEL, operations, Duration.ofMinutes(2));
        System.out.println("SCALE: " + String.join("; ", steps));
        System.out.println("SCALE: " + completedReads + " reads, " + completedWrites + " writes, " + unknown
                + " unknown, " + clientErrors.size() + " client errors -> " + result);
        clientErrors.forEach(error -> System.out.println("SCALE client error: " + error));

        assertThat(clientErrors)
                .as("every operation must eventually succeed while members come and go")
                .isEmpty();
        assertThat(completedReads).isGreaterThan(100);
        assertThat(completedWrites).isGreaterThan(100);
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
