/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.transport.grpc;

import dev.flotilla.core.ClusterConfig;
import dev.flotilla.core.ConfChange;
import dev.flotilla.core.NodeId;
import dev.flotilla.transport.AdminEndpoint;
import dev.flotilla.transport.ClusterStatus;
import dev.flotilla.wire.v1.AdminServiceGrpc;
import dev.flotilla.wire.v1.DescribeClusterRequest;
import dev.flotilla.wire.v1.TransferLeadershipRequest;
import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.ManagedChannel;
import io.grpc.stub.StreamObserver;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

public final class GrpcAdminEndpoint implements AdminEndpoint {

    private final ConcurrentMap<InetSocketAddress, ManagedChannel> channels = new ConcurrentHashMap<>();

    @Override
    public CompletableFuture<ClusterConfig> changeMembership(
            InetSocketAddress target, ConfChange change, Duration deadline) {
        Objects.requireNonNull(change, "change");
        CompletableFuture<ClusterConfig> result = new CompletableFuture<>();
        stub(target, deadline)
                .changeMembership(
                        AdminCodec.encode(change),
                        observer(result, response -> AdminCodec.decode(response.getConfiguration())));
        return result;
    }

    @Override
    public CompletableFuture<NodeId> transferLeadership(InetSocketAddress target, NodeId leader, Duration deadline) {
        Objects.requireNonNull(leader, "leader");
        CompletableFuture<NodeId> result = new CompletableFuture<>();
        stub(target, deadline)
                .transferLeadership(
                        TransferLeadershipRequest.newBuilder()
                                .setTarget(leader.value())
                                .build(),
                        observer(result, response -> AdminCodec.node(response.getLeader())));
        return result;
    }

    @Override
    public CompletableFuture<ClusterStatus> describeCluster(
            InetSocketAddress target, boolean leaderOnly, Duration deadline) {
        CompletableFuture<ClusterStatus> result = new CompletableFuture<>();
        stub(target, deadline)
                .describeCluster(
                        DescribeClusterRequest.newBuilder()
                                .setLeaderOnly(leaderOnly)
                                .build(),
                        observer(result, AdminCodec::decode));
        return result;
    }

    @Override
    public void close() {
        channels.values().forEach(ManagedChannel::shutdownNow);
        channels.clear();
    }

    private AdminServiceGrpc.AdminServiceStub stub(InetSocketAddress target, Duration deadline) {
        Objects.requireNonNull(target, "target");
        return AdminServiceGrpc.newStub(channels.computeIfAbsent(
                        target,
                        address -> Grpc.newChannelBuilderForAddress(
                                        address.getHostString(), address.getPort(), InsecureChannelCredentials.create())
                                .build()))
                .withDeadlineAfter(deadline.toNanos(), TimeUnit.NANOSECONDS);
    }

    private static <R, T> StreamObserver<R> observer(CompletableFuture<T> result, Function<R, T> decode) {
        return new StreamObserver<>() {
            @Override
            public void onNext(R response) {
                try {
                    result.complete(decode.apply(response));
                } catch (WireFormatException malformed) {
                    result.completeExceptionally(malformed);
                }
            }

            @Override
            public void onError(Throwable error) {
                result.completeExceptionally(GrpcClientEndpoint.toFailure(error));
            }

            @Override
            public void onCompleted() {}
        };
    }
}
