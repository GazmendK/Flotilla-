/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.transport.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import dev.flotilla.core.NodeId;
import dev.flotilla.core.message.RaftMessage;
import dev.flotilla.core.message.TimeoutNowRequest;
import dev.flotilla.transport.PeerDirectory;
import dev.flotilla.transport.TransportConfig;
import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GrpcPeerTransportTest {

    private static final NodeId SELF = NodeId.of("self");
    private static final NodeId PEER = NodeId.of("peer");
    private static final Duration PATIENCE = Duration.ofSeconds(20);
    private static final int INFLIGHT = 4;

    private static final TransportConfig CONFIG =
            TransportConfig.defaults().withMaxInflightPerPeer(INFLIGHT).withDeliverDeadline(Duration.ofMillis(100));

    private static InetSocketAddress freeLoopbackAddress() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return new InetSocketAddress(InetAddress.getLoopbackAddress(), socket.getLocalPort());
        }
    }

    private static boolean await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + PATIENCE.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(10);
        }
        return condition.getAsBoolean();
    }

    private static TimeoutNowRequest message() {
        return new TimeoutNowRequest(SELF, PEER, 1);
    }

    @Test
    @DisplayName("a dead peer costs a counter, never an exception, a blocked caller or unbounded memory")
    void aDeadPeerIsAbsorbed() throws Exception {
        int attempts = 10_000;
        PeerDirectory directory = PeerDirectory.of(Map.of(PEER, freeLoopbackAddress()));

        try (GrpcPeerTransport transport = new GrpcPeerTransport(SELF, directory, CONFIG)) {
            for (int i = 0; i < attempts; i++) {
                transport.send(message());
                assertThat(transport.inflight(PEER))
                        .as("the number of deliveries in flight to one peer is bounded")
                        .isLessThanOrEqualTo(INFLIGHT);
            }

            assertThat(await(() -> transport.inflight(PEER) == 0)).isTrue();
            assertThat(transport.delivered()).isZero();
            assertThat(transport.dropped() + transport.failed())
                    .as("every message is accounted for: refused up front or failed in flight")
                    .isEqualTo(attempts);
        }
    }

    @Test
    void aPeerWithNoKnownAddressIsDropped() {
        try (GrpcPeerTransport transport = new GrpcPeerTransport(SELF, PeerDirectory.of(Map.of()), CONFIG)) {
            transport.send(message());

            assertThat(transport.dropped()).isEqualTo(1);
            assertThat(transport.inflight(PEER)).isZero();
        }
    }

    @Test
    void aMessageToItselfIsNeverSent() {
        try (GrpcPeerTransport transport = new GrpcPeerTransport(SELF, PeerDirectory.of(Map.of()), CONFIG)) {
            transport.send(new TimeoutNowRequest(PEER, SELF, 1));

            assertThat(transport.dropped()).isEqualTo(1);
        }
    }

    @Test
    void sendingAfterCloseIsDropped() throws Exception {
        PeerDirectory directory = PeerDirectory.of(Map.of(PEER, freeLoopbackAddress()));
        GrpcPeerTransport transport = new GrpcPeerTransport(SELF, directory, CONFIG);
        transport.close();

        transport.send(message());

        assertThat(transport.dropped()).isEqualTo(1);
    }

    @Test
    @DisplayName("a peer that comes back is reached again without anyone intervening")
    void aReturningPeerIsReconnected() throws Exception {
        InetSocketAddress address = freeLoopbackAddress();
        List<RaftMessage> received = new CopyOnWriteArrayList<>();

        try (GrpcPeerTransport transport =
                new GrpcPeerTransport(SELF, PeerDirectory.of(Map.of(PEER, address)), CONFIG)) {
            transport.send(message());
            assertThat(await(() -> transport.failed() + transport.dropped() >= 1))
                    .isTrue();

            Server peer = NettyServerBuilder.forAddress(address)
                    .addService(new GrpcPeerService(PEER, received::add))
                    .build()
                    .start();
            try {
                assertThat(await(() -> {
                            transport.send(message());
                            return !received.isEmpty();
                        }))
                        .as("gRPC reconnects with jittered backoff; the transport must not have given up")
                        .isTrue();
                assertThat(transport.delivered()).isPositive();
            } finally {
                peer.shutdownNow();
                peer.awaitTermination(5, TimeUnit.SECONDS);
            }
        }
    }
}
