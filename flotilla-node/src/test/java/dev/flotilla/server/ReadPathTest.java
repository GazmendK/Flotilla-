/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.flotilla.core.Bytes;
import dev.flotilla.core.ClusterConfig;
import dev.flotilla.core.NodeId;
import dev.flotilla.core.RaftConfig;
import dev.flotilla.core.message.AppendEntriesRequest;
import dev.flotilla.kv.Command;
import dev.flotilla.kv.CommandCodec;
import dev.flotilla.kv.KvRequest;
import dev.flotilla.kv.KvResponse;
import dev.flotilla.kv.KvStateMachine;
import dev.flotilla.storage.FsyncPolicy;
import dev.flotilla.storage.StorageConfig;
import dev.flotilla.transport.ReadConsistency;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReadPathTest {

    private static final List<NodeId> IDS = List.of(NodeId.of("n1"), NodeId.of("n2"), NodeId.of("n3"));
    private static final Duration PATIENCE = Duration.ofSeconds(30);
    private static final Bytes KEY = Bytes.ofUtf8("colour");

    @TempDir
    Path directory;

    private final Map<NodeId, RaftServer> servers = new ConcurrentHashMap<>();
    private final Set<NodeId> isolated = ConcurrentHashMap.newKeySet();
    private final Set<NodeId> starvedOfEntries = ConcurrentHashMap.newKeySet();

    private void startCluster(UnaryOperator<RaftConfig.Builder> tuning) {
        for (NodeId id : IDS) {
            servers.put(
                    id,
                    RaftServer.start(
                            ServerConfig.defaults().withTickInterval(Duration.ofMillis(5)),
                            tuning.apply(RaftConfig.builder(id)).build(),
                            ClusterConfig.ofVoters(IDS),
                            StorageConfig.of(directory.resolve(id.value())).withFsyncPolicy(FsyncPolicy.NEVER),
                            new KvStateMachine(),
                            message -> {
                                RaftServer recipient = servers.get(message.to());
                                if (recipient != null
                                        && !isolated.contains(message.from())
                                        && !isolated.contains(message.to())
                                        && !(message instanceof AppendEntriesRequest
                                                && starvedOfEntries.contains(message.to()))) {
                                    recipient.deliver(message);
                                }
                            }));
        }
    }

    @AfterEach
    void stopCluster() {
        servers.values().forEach(RaftServer::close);
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

    private RaftServer server(NodeId id) {
        RaftServer server = servers.get(id);
        if (server == null) {
            throw new AssertionError("unknown server " + id);
        }
        return server;
    }

    private NodeId aFollower(NodeId leader) {
        return IDS.stream().filter(id -> !id.equals(leader)).findFirst().orElseThrow();
    }

    private long write(NodeId leader, String value) throws Exception {
        Bytes command = CommandCodec.encode(KvRequest.anonymous(new Command.Put(KEY, Bytes.ofUtf8(value))));
        return server(leader)
                .submit(command)
                .get(PATIENCE.toSeconds(), TimeUnit.SECONDS)
                .index();
    }

    private @Nullable String read(NodeId node, ReadConsistency consistency) throws Exception {
        Bytes query = CommandCodec.encode(new Command.Get(KEY));
        Bytes response = server(node)
                .query(query, consistency)
                .get(PATIENCE.toSeconds(), TimeUnit.SECONDS)
                .response();
        Optional<Bytes> value = ((KvResponse.Value) CommandCodec.decodeResponse(response)).asOptional();
        return value.map(Bytes::toUtf8).orElse(null);
    }

    @Test
    @DisplayName("a linearizable read on the leader sees the last write and adds nothing to the log")
    void aLeaderReadSeesTheWriteWithoutTouchingTheLog() throws Exception {
        startCluster(UnaryOperator.identity());
        NodeId leader = awaitLeader();
        long written = write(leader, "blue");
        long committed = server(leader).commitIndex();

        assertThat(read(leader, ReadConsistency.LINEARIZABLE)).isEqualTo("blue");
        assertThat(read(leader, ReadConsistency.LINEARIZABLE)).isEqualTo("blue");

        assertThat(committed).isGreaterThanOrEqualTo(written);
        assertThat(server(leader).commitIndex())
                .as("a read that went through the log would have committed an entry of its own")
                .isEqualTo(committed);
    }

    @Test
    @DisplayName("a linearizable read on a follower sees a write the follower may not have applied yet")
    void aFollowerReadSeesTheLatestWrite() throws Exception {
        startCluster(UnaryOperator.identity());
        NodeId leader = awaitLeader();
        NodeId follower = aFollower(leader);

        for (int i = 0; i < 20; i++) {
            write(leader, "v" + i);
            assertThat(read(follower, ReadConsistency.LINEARIZABLE)).isEqualTo("v" + i);
        }
    }

    @Test
    @DisplayName("a follower cut off from the leader answers a stale read and refuses a linearizable one")
    void anIsolatedFollowerIsStaleButNeverLinearizablyWrong() throws Exception {
        startCluster(UnaryOperator.identity());
        NodeId leader = awaitLeader();
        NodeId follower = aFollower(leader);
        write(leader, "old");
        assertThat(read(follower, ReadConsistency.LINEARIZABLE)).isEqualTo("old");

        isolated.add(follower);
        write(leader, "new");

        assertThat(read(follower, ReadConsistency.STALE))
                .as("a stale read is allowed to be stale, which is why it is not the default")
                .isEqualTo("old");
        assertThatThrownBy(() -> read(follower, ReadConsistency.LINEARIZABLE))
                .isInstanceOf(ExecutionException.class)
                .cause()
                .isInstanceOfAny(NotLeaderException.class, ReadTimeoutException.class);
    }

    @Test
    @DisplayName("with leases enabled a leader answers a read at once from a recent majority acknowledgement")
    void aLeaseReadIsAnswered() throws Exception {
        startCluster(builder -> builder.leaseReads(true));
        NodeId leader = awaitLeader();
        write(leader, "leased");

        assertThat(read(leader, ReadConsistency.LEASE)).isEqualTo("leased");
    }

    @Test
    @DisplayName("a command that would change state is refused as a query")
    void aWriteIsRefusedAsAQuery() throws Exception {
        startCluster(UnaryOperator.identity());
        NodeId leader = awaitLeader();
        Bytes put = CommandCodec.encode(new Command.Put(KEY, Bytes.ofUtf8("sneaky")));

        assertThatThrownBy(() -> server(leader)
                        .query(put, ReadConsistency.LINEARIZABLE)
                        .get(PATIENCE.toSeconds(), TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasMessageContaining("must not change state");
        assertThat(read(leader, ReadConsistency.LINEARIZABLE)).isNull();
    }

    @Test
    @DisplayName("a follower that is behind waits for its own state machine before answering a linearizable read")
    void aLaggingFollowerWaitsForItsStateMachine() throws Exception {
        startCluster(UnaryOperator.identity());
        NodeId leader = awaitLeader();
        NodeId follower = aFollower(leader);
        write(leader, "before");
        assertThat(read(follower, ReadConsistency.LINEARIZABLE)).isEqualTo("before");

        starvedOfEntries.add(follower);
        write(leader, "after");
        CompletableFuture<Applied> pending =
                server(follower).query(CommandCodec.encode(new Command.Get(KEY)), ReadConsistency.LINEARIZABLE);
        Thread.sleep(300);

        assertThat(pending)
                .as("the leader has confirmed a read index the follower has not applied, so it must not answer yet")
                .isNotDone();

        starvedOfEntries.clear();
        Bytes response = pending.get(PATIENCE.toSeconds(), TimeUnit.SECONDS).response();
        assertThat(((KvResponse.Value) CommandCodec.decodeResponse(response)).asOptional())
                .contains(Bytes.ofUtf8("after"));
    }
}
