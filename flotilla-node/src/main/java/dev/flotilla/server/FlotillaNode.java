/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.server;

import dev.flotilla.core.ClusterConfig;
import dev.flotilla.core.RaftConfig;
import dev.flotilla.kv.StateMachine;
import dev.flotilla.storage.StorageConfig;
import dev.flotilla.transport.PeerDirectory;
import dev.flotilla.transport.TransportConfig;
import dev.flotilla.transport.grpc.GrpcPeerService;
import dev.flotilla.transport.grpc.GrpcPeerTransport;
import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

public final class FlotillaNode implements AutoCloseable {

    static final long APPEND_ENVELOPE_OVERHEAD_BYTES = 64 * 1024;

    private final RaftServer server;
    private final GrpcPeerTransport transport;
    private final GrpcPeerService service;
    private final Server grpc;

    private FlotillaNode(RaftServer server, GrpcPeerTransport transport, GrpcPeerService service, Server grpc) {
        this.server = server;
        this.transport = transport;
        this.service = service;
        this.grpc = grpc;
    }

    public static FlotillaNode start(
            ServerConfig serverConfig,
            RaftConfig raftConfig,
            ClusterConfig cluster,
            StorageConfig storageConfig,
            StateMachine stateMachine,
            TransportConfig transportConfig,
            PeerDirectory peers,
            InetSocketAddress bindAddress) {
        Objects.requireNonNull(serverConfig, "serverConfig");
        Objects.requireNonNull(raftConfig, "raftConfig");
        Objects.requireNonNull(transportConfig, "transportConfig");
        Objects.requireNonNull(peers, "peers");
        Objects.requireNonNull(bindAddress, "bindAddress");
        validateTiming(serverConfig, raftConfig, transportConfig);

        GrpcPeerTransport transport = new GrpcPeerTransport(raftConfig.nodeId(), peers, transportConfig);
        RaftServer server =
                RaftServer.start(serverConfig, raftConfig, cluster, storageConfig, stateMachine, transport::send);
        GrpcPeerService service = new GrpcPeerService(raftConfig.nodeId(), server::deliver);
        try {
            Server grpc = NettyServerBuilder.forAddress(bindAddress)
                    .addService(service)
                    .maxInboundMessageSize(transportConfig.maxMessageBytes())
                    .permitKeepAliveTime(transportConfig.keepAliveTime().toNanos() / 2, TimeUnit.NANOSECONDS)
                    .permitKeepAliveWithoutCalls(true)
                    .build()
                    .start();
            return new FlotillaNode(server, transport, service, grpc);
        } catch (IOException bindFailure) {
            server.close();
            transport.close();
            throw new UncheckedIOException("Cannot listen on " + bindAddress, bindFailure);
        }
    }

    static void validateTiming(ServerConfig serverConfig, RaftConfig raftConfig, TransportConfig transportConfig) {
        Duration electionTimeout = serverConfig.tickInterval().multipliedBy(raftConfig.electionTimeoutMinTicks());
        if (transportConfig.deliverDeadline().compareTo(electionTimeout) >= 0) {
            throw new IllegalArgumentException("deliverDeadline (" + transportConfig.deliverDeadline()
                    + ") must be shorter than the minimum election timeout (" + electionTimeout + " = "
                    + raftConfig.electionTimeoutMinTicks() + " ticks of " + serverConfig.tickInterval()
                    + "): a delivery allowed to outlast an election timeout holds a peer's slot past the "
                    + "point where its followers start an election");
        }
        long largestAppend = raftConfig.maxAppendBytes() + APPEND_ENVELOPE_OVERHEAD_BYTES;
        if (largestAppend > transportConfig.maxMessageBytes()) {
            throw new IllegalArgumentException("maxAppendBytes (" + raftConfig.maxAppendBytes() + ") plus "
                    + APPEND_ENVELOPE_OVERHEAD_BYTES + " bytes of framing exceeds maxMessageBytes ("
                    + transportConfig.maxMessageBytes() + "): the largest batch a leader may send could "
                    + "never be delivered, and a follower that needs it would never catch up");
        }
    }

    public RaftServer server() {
        return server;
    }

    public GrpcPeerTransport transport() {
        return transport;
    }

    public GrpcPeerService service() {
        return service;
    }

    public InetSocketAddress address() {
        return (InetSocketAddress) grpc.getListenSockets().getFirst();
    }

    @Override
    public void close() {
        grpc.shutdownNow();
        try {
            grpc.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        server.close();
        transport.close();
    }
}
