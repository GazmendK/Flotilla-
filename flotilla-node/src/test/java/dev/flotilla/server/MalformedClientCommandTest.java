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
import dev.flotilla.kv.KvStateMachine;
import dev.flotilla.storage.FsyncPolicy;
import dev.flotilla.storage.StorageConfig;
import dev.flotilla.transport.CallFailure;
import dev.flotilla.transport.Executed;
import dev.flotilla.transport.PeerDirectory;
import dev.flotilla.transport.TransportConfig;
import dev.flotilla.transport.grpc.GrpcClientEndpoint;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MalformedClientCommandTest {

    private static final NodeId N1 = NodeId.of("n1");
    private static final Duration PATIENCE = Duration.ofSeconds(15);
    private static final Bytes GARBAGE = Bytes.ofUtf8("not a command");

    @TempDir
    Path directory;

    private FlotillaNode start() {
        return FlotillaNode.start(
                ServerConfig.defaults().withTickInterval(Duration.ofMillis(5)),
                RaftConfig.defaults(N1),
                ClusterConfig.ofVoters(N1),
                StorageConfig.of(directory).withFsyncPolicy(FsyncPolicy.NEVER),
                new KvStateMachine(),
                TransportConfig.defaults().withDeliverDeadline(Duration.ofMillis(25)),
                PeerDirectory.of(Map.of()),
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
    }

    private static CallFailure failureOf(CompletableFuture<Executed> call) {
        try {
            call.get(15, TimeUnit.SECONDS);
        } catch (Exception failure) {
            return CallFailure.from(failure.getCause() == null ? failure : failure.getCause());
        }
        throw new AssertionError("expected the call to fail");
    }

    @Test
    @DisplayName("a command the state machine cannot read is refused before it is proposed, and the node keeps serving")
    void garbageIsRefusedAtTheEdge() throws Exception {
        try (FlotillaNode node = start();
                GrpcClientEndpoint endpoint = new GrpcClientEndpoint(16 * 1024 * 1024)) {
            assertThat(node.server().awaitLeadership(PATIENCE)).isTrue();

            assertThat(failureOf(endpoint.execute(node.address(), GARBAGE, PATIENCE))
                            .kind())
                    .isEqualTo(CallFailure.Kind.INVALID);

            Executed valid = endpoint.execute(
                            node.address(), CommandCodec.encode(KvRequest.anonymous(Command.put("k", "v"))), PATIENCE)
                    .get(15, TimeUnit.SECONDS);
            assertThat(valid.index()).isPositive();
            assertThat(node.server().failure()).isEmpty();
        }
    }

    @Test
    @DisplayName("without that check the same bytes would have stopped the apply thread for good")
    void unvalidatedGarbageStopsTheApplyThread() throws Exception {
        try (FlotillaNode node = start()) {
            assertThat(node.server().awaitLeadership(PATIENCE)).isTrue();

            CompletableFuture<Long> _ = node.server().propose(GARBAGE);

            long deadline = System.nanoTime() + PATIENCE.toNanos();
            while (node.server().failure().isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            assertThat(node.server().failure())
                    .as("the state machine throws on apply, and the apply loop treats that as fatal")
                    .containsInstanceOf(IllegalArgumentException.class);
        }
    }
}
