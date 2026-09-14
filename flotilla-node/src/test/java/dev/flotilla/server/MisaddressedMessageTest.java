/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.flotilla.core.ClusterConfig;
import dev.flotilla.core.NodeId;
import dev.flotilla.core.RaftConfig;
import dev.flotilla.core.message.TimeoutNowRequest;
import dev.flotilla.kv.Command;
import dev.flotilla.kv.CommandCodec;
import dev.flotilla.kv.KvRequest;
import dev.flotilla.kv.KvStateMachine;
import dev.flotilla.storage.FsyncPolicy;
import dev.flotilla.storage.StorageConfig;
import dev.flotilla.transport.PeerDirectory;
import dev.flotilla.transport.TransportConfig;
import dev.flotilla.transport.grpc.MessageCodec;
import dev.flotilla.wire.v1.RaftPeerServiceGrpc;
import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MisaddressedMessageTest {

    private static final NodeId N1 = NodeId.of("n1");
    private static final NodeId PEER = NodeId.of("n2");
    private static final NodeId ELSEWHERE = NodeId.of("elsewhere");
    private static final Duration PATIENCE = Duration.ofSeconds(15);

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

    @Test
    @DisplayName("a message addressed to another node is refused at the edge, and the node keeps running")
    void misaddressedMessagesAreRefusedAtTheEdge() throws Exception {
        try (FlotillaNode node = start()) {
            assertThat(node.server().awaitLeadership(PATIENCE)).isTrue();
            ManagedChannel channel = Grpc.newChannelBuilderForAddress(
                            node.address().getHostString(),
                            node.address().getPort(),
                            InsecureChannelCredentials.create())
                    .build();
            try {
                RaftPeerServiceGrpc.RaftPeerServiceBlockingStub stub = RaftPeerServiceGrpc.newBlockingStub(channel);
                assertThatThrownBy(() -> stub.deliver(MessageCodec.encode(new TimeoutNowRequest(PEER, ELSEWHERE, 99))))
                        .isInstanceOfSatisfying(
                                StatusRuntimeException.class,
                                failure -> assertThat(failure.getStatus().getCode())
                                        .isEqualTo(Status.Code.FAILED_PRECONDITION));
            } finally {
                channel.shutdownNow();
            }

            assertThat(node.service().refused()).isEqualTo(1);
            assertThat(node.server().failure()).isEmpty();
            long index = node.server()
                    .propose(CommandCodec.encode(KvRequest.anonymous(Command.put("k", "v"))))
                    .get(15, TimeUnit.SECONDS);
            assertThat(index).isPositive();
        }
    }

    @Test
    @DisplayName("without that check the same message would have stopped the event loop for good")
    void theCoreStillStopsOnAMisaddressedMessage() throws Exception {
        try (FlotillaNode node = start()) {
            assertThat(node.server().awaitLeadership(PATIENCE)).isTrue();

            node.server().deliver(new TimeoutNowRequest(PEER, ELSEWHERE, 99));

            long deadline = System.nanoTime() + PATIENCE.toNanos();
            while (node.server().failure().isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            assertThat(node.server().failure())
                    .as("this is why the transport checks the recipient before anything reaches the queue")
                    .containsInstanceOf(IllegalArgumentException.class);
        }
    }
}
