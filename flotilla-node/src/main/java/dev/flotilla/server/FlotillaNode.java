/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.server;

import dev.flotilla.core.ClusterConfig;
import dev.flotilla.core.ConfChange;
import dev.flotilla.core.NodeId;
import dev.flotilla.core.RaftConfig;
import dev.flotilla.kv.StateMachine;
import dev.flotilla.storage.StorageConfig;
import dev.flotilla.transport.AdminGateway;
import dev.flotilla.transport.CallFailure;
import dev.flotilla.transport.ClusterStatus;
import dev.flotilla.transport.CommandGateway;
import dev.flotilla.transport.Executed;
import dev.flotilla.transport.PeerDirectory;
import dev.flotilla.transport.QueryGateway;
import dev.flotilla.transport.TransportConfig;
import dev.flotilla.transport.grpc.GrpcAdminService;
import dev.flotilla.transport.grpc.GrpcClientService;
import dev.flotilla.transport.grpc.GrpcPeerService;
import dev.flotilla.transport.grpc.GrpcPeerTransport;
import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
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
        GrpcPeerService service = new GrpcPeerService(
                raftConfig.nodeId(),
                message -> {
                    transport.peerIsAlive(message.from());
                    server.deliver(message);
                },
                transportConfig.maxSnapshotBytes());
        CommandGateway gateway = gateway(server, stateMachine, peers);
        try {
            Server grpc = NettyServerBuilder.forAddress(bindAddress)
                    .addService(service)
                    .addService(new GrpcClientService(gateway, queries(server, stateMachine, peers)))
                    .addService(new GrpcAdminService(admin(server, peers)))
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

    static QueryGateway queries(RaftServer server, StateMachine stateMachine, PeerDirectory peers) {
        return (query, consistency) -> {
            try {
                stateMachine.validateQuery(query);
            } catch (IllegalArgumentException invalid) {
                return CompletableFuture.failedFuture(
                        CallFailure.of(CallFailure.Kind.INVALID, String.valueOf(invalid.getMessage())));
            }
            return server.query(query, consistency)
                    .thenApply(answered -> new Executed(answered.index(), answered.response()))
                    .exceptionallyCompose(failure -> CompletableFuture.failedFuture(translate(failure, peers)));
        };
    }

    static AdminGateway admin(RaftServer server, PeerDirectory peers) {
        return new AdminGateway() {
            @Override
            public CompletableFuture<ClusterConfig> changeMembership(ConfChange change) {
                if (change instanceof ConfChange.AddLearner add
                        && server.isLeader()
                        && peers.addressOf(add.node()).isEmpty()) {
                    return CompletableFuture.failedFuture(CallFailure.of(
                            CallFailure.Kind.REJECTED,
                            add.node() + " has no address in the peer directory of " + server.id()
                                    + "; a learner the leader cannot reach can never catch up"));
                }
                return translated(server.changeMembership(change), peers);
            }

            @Override
            public CompletableFuture<NodeId> transferLeadership(NodeId target) {
                return translated(server.transferLeadership(target), peers);
            }

            @Override
            public CompletableFuture<ClusterStatus> describeCluster(boolean leaderOnly) {
                return translated(server.describeCluster(leaderOnly), peers);
            }
        };
    }

    private static <T> CompletableFuture<T> translated(CompletableFuture<T> outcome, PeerDirectory peers) {
        return outcome.exceptionallyCompose(failure -> CompletableFuture.failedFuture(translate(failure, peers)));
    }

    static CommandGateway gateway(RaftServer server, StateMachine stateMachine, PeerDirectory peers) {
        return command -> {
            try {
                stateMachine.validate(command);
            } catch (IllegalArgumentException invalid) {
                return CompletableFuture.failedFuture(
                        CallFailure.of(CallFailure.Kind.INVALID, String.valueOf(invalid.getMessage())));
            }
            return server.submit(command)
                    .thenApply(applied -> new Executed(applied.index(), applied.response()))
                    .exceptionallyCompose(failure -> CompletableFuture.failedFuture(translate(failure, peers)));
        };
    }

    static CallFailure translate(Throwable failure, PeerDirectory peers) {
        Throwable cause =
                failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
        return switch (cause) {
            case NotLeaderException notLeader ->
                CallFailure.notLeader(
                        notLeader.leader().orElse(null),
                        notLeader.leader().flatMap(peers::addressOf).orElse(null));
            case ChangeRejectedException rejected ->
                CallFailure.of(CallFailure.Kind.REJECTED, String.valueOf(rejected.getMessage()));
            case BackpressureException overloaded ->
                CallFailure.of(CallFailure.Kind.OVERLOADED, String.valueOf(overloaded.getMessage()));
            case CallFailure callFailure -> callFailure;
            default -> CallFailure.of(CallFailure.Kind.UNAVAILABLE, String.valueOf(cause.getMessage()));
        };
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
