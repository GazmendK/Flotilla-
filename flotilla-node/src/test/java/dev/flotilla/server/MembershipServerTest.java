/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.flotilla.core.Bytes;
import dev.flotilla.core.ClusterConfig;
import dev.flotilla.core.ConfChange;
import dev.flotilla.core.NodeId;
import dev.flotilla.core.RaftConfig;
import dev.flotilla.kv.Command;
import dev.flotilla.kv.CommandCodec;
import dev.flotilla.kv.KvRequest;
import dev.flotilla.kv.KvStateMachine;
import dev.flotilla.storage.FsyncPolicy;
import dev.flotilla.storage.StorageConfig;
import dev.flotilla.transport.CallFailure;
import dev.flotilla.transport.ClusterStatus;
import dev.flotilla.transport.PeerDirectory;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MembershipServerTest {

    private static final NodeId N1 = NodeId.of("n1");
    private static final NodeId N2 = NodeId.of("n2");
    private static final NodeId N3 = NodeId.of("n3");
    private static final NodeId N4 = NodeId.of("n4");
    private static final ClusterConfig TRIO = ClusterConfig.ofVoters(N1, N2, N3);
    private static final Duration PATIENCE = Duration.ofSeconds(30);

    @TempDir
    Path directory;

    private final Map<NodeId, RaftServer> servers = new ConcurrentHashMap<>();
    private final Set<NodeId> isolated = ConcurrentHashMap.newKeySet();

    private void start(NodeId id, SnapshotPolicy policy) {
        servers.put(
                id,
                RaftServer.start(
                        ServerConfig.defaults()
                                .withTickInterval(Duration.ofMillis(5))
                                .withSnapshotPolicy(policy),
                        RaftConfig.defaults(id),
                        TRIO,
                        StorageConfig.of(directory.resolve(id.value())).withFsyncPolicy(FsyncPolicy.NEVER),
                        new KvStateMachine(),
                        message -> {
                            RaftServer recipient = servers.get(message.to());
                            if (recipient != null
                                    && !isolated.contains(message.from())
                                    && !isolated.contains(message.to())) {
                                recipient.deliver(message);
                            }
                        }));
    }

    private void startAll(SnapshotPolicy policy) {
        List.of(N1, N2, N3, N4).forEach(id -> start(id, policy));
    }

    @AfterEach
    void stopAll() {
        servers.values().forEach(RaftServer::close);
    }

    private RaftServer server(NodeId id) {
        RaftServer server = servers.get(id);
        if (server == null) {
            throw new AssertionError("unknown server " + id);
        }
        return server;
    }

    private NodeId awaitLeader() throws InterruptedException {
        long deadline = System.nanoTime() + PATIENCE.toNanos();
        while (System.nanoTime() < deadline) {
            for (Map.Entry<NodeId, RaftServer> entry : servers.entrySet()) {
                if (!isolated.contains(entry.getKey()) && entry.getValue().isLeader()) {
                    return entry.getKey();
                }
            }
            Thread.sleep(5);
        }
        throw new AssertionError("no leader emerged");
    }

    private static void await(BooleanSupplier condition, String what) throws InterruptedException {
        long deadline = System.nanoTime() + PATIENCE.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timed out waiting until " + what);
            }
            Thread.sleep(5);
        }
    }

    private static <T> T get(CompletableFuture<T> future) throws Exception {
        return future.get(PATIENCE.toSeconds(), TimeUnit.SECONDS);
    }

    private void write(NodeId leader, String key, String value) throws Exception {
        get(server(leader)
                .submit(CommandCodec.encode(
                        KvRequest.anonymous(new Command.Put(Bytes.ofUtf8(key), Bytes.ofUtf8(value))))));
    }

    private ClusterConfig promoteWhenCaughtUp(NodeId leader, NodeId learner) throws Exception {
        await(
                () -> {
                    try {
                        ClusterStatus status = get(server(leader).describeCluster());
                        return status.catchUp().containsKey(learner)
                                && status.catchUp().get(learner).caughtUp();
                    } catch (Exception unavailable) {
                        return false;
                    }
                },
                learner + " has caught up");
        return get(server(leader).changeMembership(new ConfChange.Promote(learner)));
    }

    @Test
    @DisplayName("a learner is added, catches up, is promoted, and every member agrees on the result")
    void aNodeJoinsAsALearnerAndIsPromoted() throws Exception {
        startAll(SnapshotPolicy.defaults());
        NodeId leader = awaitLeader();
        write(leader, "k", "v");

        ClusterConfig withLearner = get(server(leader).changeMembership(new ConfChange.AddLearner(N4)));
        assertThat(withLearner.learners()).containsExactly(N4);

        ClusterConfig promoted = promoteWhenCaughtUp(leader, N4);

        assertThat(promoted.voters()).containsExactly(N1, N2, N3, N4);
        for (NodeId id : List.of(N1, N2, N3, N4)) {
            await(() -> server(id).configuration().equals(promoted), id + " has the new configuration");
        }
        ClusterStatus status = get(server(leader).describeCluster());
        assertThat(status.configurationCommitted()).isTrue();
        assertThat(status.knownLeader()).contains(leader);
    }

    @Test
    @DisplayName("a second change sent while the first is in flight waits for it instead of being refused")
    void changesSentTogetherAreMadeOneAfterTheOther() throws Exception {
        startAll(SnapshotPolicy.defaults());
        NodeId leader = awaitLeader();
        write(leader, "k", "v");

        CompletableFuture<ClusterConfig> first = server(leader).changeMembership(new ConfChange.AddLearner(N4));
        CompletableFuture<ClusterConfig> second =
                server(leader).changeMembership(new ConfChange.AddLearner(NodeId.of("n5")));

        assertThat(get(first).learners()).containsExactly(N4);
        assertThat(get(second).learners()).containsExactly(N4, NodeId.of("n5"));
    }

    @Test
    @DisplayName("a node the leader cannot resolve is refused as a learner, because it could never catch up")
    void aLearnerNeedsAnAddress() throws Exception {
        startAll(SnapshotPolicy.defaults());
        NodeId leader = awaitLeader();
        write(leader, "k", "v");

        assertThatThrownBy(() -> get(FlotillaNode.admin(server(leader), PeerDirectory.of(Map.of()))
                        .changeMembership(new ConfChange.AddLearner(N4))))
                .isInstanceOf(ExecutionException.class)
                .cause()
                .isInstanceOfSatisfying(
                        CallFailure.class, failure -> assertThat(failure.kind()).isEqualTo(CallFailure.Kind.REJECTED))
                .hasMessageContaining("no address");
        assertThat(server(leader).configuration().learners()).isEmpty();
    }

    @Test
    @DisplayName("a refused change says why, and a follower names the leader instead of deciding")
    void refusalsCarryTheirReason() throws Exception {
        startAll(SnapshotPolicy.defaults());
        NodeId leader = awaitLeader();
        NodeId follower = List.of(N1, N2, N3).stream()
                .filter(id -> !id.equals(leader))
                .findFirst()
                .orElseThrow();

        assertThatThrownBy(() -> get(server(leader).changeMembership(new ConfChange.Promote(N4))))
                .isInstanceOf(ExecutionException.class)
                .cause()
                .isInstanceOf(ChangeRejectedException.class)
                .hasMessageContaining("n4");
        assertThatThrownBy(() -> get(server(follower).changeMembership(new ConfChange.AddLearner(N4))))
                .isInstanceOf(ExecutionException.class)
                .cause()
                .isInstanceOfSatisfying(
                        NotLeaderException.class,
                        notLeader -> assertThat(notLeader.leader()).contains(leader));
    }

    @Test
    @DisplayName("a leader that removes itself reports the removal as done, not as a lost leadership")
    void aLeaderRemovingItselfSeesTheChangeSucceed() throws Exception {
        startAll(SnapshotPolicy.defaults());
        NodeId leader = awaitLeader();
        write(leader, "k", "v");

        ClusterConfig after = get(server(leader).changeMembership(new ConfChange.Remove(leader)));

        assertThat(after.voters()).doesNotContain(leader).hasSize(2);
        await(() -> !server(leader).isLeader(), leader + " has stepped down");
        isolated.add(leader);
        NodeId successor = awaitLeader();
        assertThat(successor).isNotEqualTo(leader);
        write(successor, "k", "after");
    }

    @Test
    @DisplayName("leadership is handed to a chosen voter, and the future completes once it leads")
    void leadershipIsHandedOver() throws Exception {
        startAll(SnapshotPolicy.defaults());
        NodeId leader = awaitLeader();
        write(leader, "k", "v");
        NodeId target = List.of(N1, N2, N3).stream()
                .filter(id -> !id.equals(leader))
                .findFirst()
                .orElseThrow();

        NodeId newLeader = get(server(leader).transferLeadership(target));

        assertThat(newLeader).isEqualTo(target);
        await(() -> server(target).isLeader(), target + " leads");
        write(target, "k", "after the handover");
    }

    @Test
    @DisplayName("a handover to an unreachable voter fails after an election timeout and the leader keeps going")
    void aHandoverToAnUnreachableVoterFails() throws Exception {
        startAll(SnapshotPolicy.defaults());
        NodeId leader = awaitLeader();
        write(leader, "k", "v");
        NodeId target = List.of(N1, N2, N3).stream()
                .filter(id -> !id.equals(leader))
                .findFirst()
                .orElseThrow();
        isolated.add(target);

        assertThatThrownBy(() -> get(server(leader).transferLeadership(target)))
                .isInstanceOf(ExecutionException.class)
                .cause()
                .isInstanceOf(ChangeRejectedException.class)
                .hasMessageContaining("did not take over");

        assertThat(server(leader).isLeader()).isTrue();
        write(leader, "k", "still writable");
    }

    @Test
    @DisplayName("a snapshot taken after a membership change carries the new configuration across a restart")
    void aSnapshotCarriesTheConfigurationItWasTakenAt() throws Exception {
        startAll(SnapshotPolicy.defaults().withEntriesBetweenSnapshots(20));
        NodeId leader = awaitLeader();
        get(server(leader).changeMembership(new ConfChange.AddLearner(N4)));
        ClusterConfig promoted = promoteWhenCaughtUp(leader, N4);
        long configurationIndex = get(server(leader).describeCluster()).configurationIndex();
        NodeId restarted = List.of(N1, N2, N3).stream()
                .filter(id -> !id.equals(leader))
                .findFirst()
                .orElseThrow();

        for (int i = 0; i < 100; i++) {
            write(leader, "k" + i, "v" + i);
        }
        await(
                () -> server(restarted).firstLogIndex() > configurationIndex,
                restarted + " has compacted the configuration entry into a snapshot");

        server(restarted).close();
        servers.remove(restarted);
        start(restarted, SnapshotPolicy.defaults().withEntriesBetweenSnapshots(20));

        assertThat(server(restarted).configuration())
                .as("the configuration entry is gone from the log; only the snapshot can remember it")
                .isEqualTo(promoted);
    }
}
