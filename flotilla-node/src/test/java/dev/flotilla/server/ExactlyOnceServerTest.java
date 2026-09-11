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
import dev.flotilla.kv.KvResponse;
import dev.flotilla.kv.KvStateMachine;
import dev.flotilla.storage.FsyncPolicy;
import dev.flotilla.storage.StorageConfig;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExactlyOnceServerTest {

    private static final NodeId N1 = NodeId.of("n1");
    private static final Duration PATIENCE = Duration.ofSeconds(15);

    @TempDir
    Path directory;

    private final KvStateMachine store = new KvStateMachine();

    private RaftServer start() {
        return RaftServer.start(
                ServerConfig.defaults().withTickInterval(Duration.ofMillis(5)),
                RaftConfig.defaults(N1),
                ClusterConfig.ofVoters(N1),
                StorageConfig.of(directory).withFsyncPolicy(FsyncPolicy.NEVER),
                store,
                MessageSink.discarding());
    }

    private static KvResponse run(RaftServer server, KvRequest request) throws Exception {
        Bytes response = server.execute(CommandCodec.encode(request)).get(15, TimeUnit.SECONDS);
        return CommandCodec.decodeResponse(response);
    }

    @Test
    @DisplayName("a client takes a lock, retries the identical request, and is still told it holds it")
    void aRetriedLockAcquisitionStaysSuccessful() throws Exception {
        try (RaftServer server = start()) {
            assertThat(server.awaitLeadership(PATIENCE)).isTrue();

            long client = ((KvResponse.Opened) run(server, KvRequest.register())).clientId();
            KvRequest take = KvRequest.of(
                    client, 1, new Command.CompareAndSwap(Bytes.ofUtf8("lock"), null, Bytes.ofUtf8("owner-1")));

            KvResponse first = run(server, take);
            KvResponse retry = run(server, take);

            assertThat(first).isEqualTo(new KvResponse.Swapped(true));
            assertThat(retry).isEqualTo(new KvResponse.Swapped(true));
            assertThat(store.get(Bytes.ofUtf8("lock"))).contains(Bytes.ofUtf8("owner-1"));
        }
    }

    @Test
    @DisplayName("the caller gets the state machine's answer back, not just an index")
    void executeReturnsTheResponse() throws Exception {
        try (RaftServer server = start()) {
            assertThat(server.awaitLeadership(PATIENCE)).isTrue();

            assertThat(run(server, KvRequest.anonymous(Command.put("k", "one"))))
                    .isEqualTo(KvResponse.ABSENT);
            assertThat(run(server, KvRequest.anonymous(Command.put("k", "two"))))
                    .isEqualTo(new KvResponse.Value(Bytes.ofUtf8("one")));
            assertThat(run(server, KvRequest.anonymous(Command.get("k"))))
                    .isEqualTo(new KvResponse.Value(Bytes.ofUtf8("two")));
        }
    }

    @Test
    @DisplayName("a retried counter increment is applied once, however often it is submitted")
    void aRetriedIncrementHappensOnce() throws Exception {
        try (RaftServer server = start()) {
            assertThat(server.awaitLeadership(PATIENCE)).isTrue();

            long client = ((KvResponse.Opened) run(server, KvRequest.register())).clientId();
            run(server, KvRequest.of(client, 1, Command.put("counter", "1")));
            KvRequest increment = KvRequest.of(
                    client,
                    2,
                    new Command.CompareAndSwap(Bytes.ofUtf8("counter"), Bytes.ofUtf8("1"), Bytes.ofUtf8("2")));

            for (int attempt = 0; attempt < 5; attempt++) {
                assertThat(run(server, increment)).as("attempt %d", attempt).isEqualTo(new KvResponse.Swapped(true));
            }

            assertThat(store.get(Bytes.ofUtf8("counter")))
                    .as("five submissions of one increment must leave the counter at two")
                    .contains(Bytes.ofUtf8("2"));
        }
    }

    @Test
    @DisplayName("the proposal index is still available alongside the response")
    void proposeStillReportsTheIndex() throws Exception {
        try (RaftServer server = start()) {
            assertThat(server.awaitLeadership(PATIENCE)).isTrue();

            long index = server.propose(CommandCodec.encode(KvRequest.anonymous(Command.put("k", "v"))))
                    .get(15, TimeUnit.SECONDS);

            assertThat(index).isPositive();
            assertThat(server.appliedIndex()).isGreaterThanOrEqualTo(index);
        }
    }
}
