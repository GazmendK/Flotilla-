/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.transport.grpc;

import com.google.protobuf.UnsafeByteOperations;
import dev.flotilla.core.Bytes;
import dev.flotilla.core.NodeId;
import dev.flotilla.transport.CallFailure;
import dev.flotilla.transport.ClientEndpoint;
import dev.flotilla.transport.Executed;
import dev.flotilla.transport.ReadConsistency;
import dev.flotilla.wire.v1.ClientServiceGrpc;
import dev.flotilla.wire.v1.ExecuteRequest;
import dev.flotilla.wire.v1.ExecuteResponse;
import dev.flotilla.wire.v1.QueryRequest;
import dev.flotilla.wire.v1.QueryResponse;
import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

public final class GrpcClientEndpoint implements ClientEndpoint {

    private final int maxMessageBytes;
    private final ConcurrentMap<InetSocketAddress, ManagedChannel> channels = new ConcurrentHashMap<>();

    public GrpcClientEndpoint(int maxMessageBytes) {
        this.maxMessageBytes = maxMessageBytes;
    }

    @Override
    public CompletableFuture<Executed> execute(InetSocketAddress target, Bytes command, Duration deadline) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(command, "command");
        CompletableFuture<Executed> result = new CompletableFuture<>();
        ClientServiceGrpc.newStub(channelTo(target))
                .withDeadlineAfter(deadline.toNanos(), TimeUnit.NANOSECONDS)
                .execute(
                        ExecuteRequest.newBuilder()
                                .setCommand(UnsafeByteOperations.unsafeWrap(command.toByteArray()))
                                .build(),
                        new StreamObserver<>() {
                            @Override
                            public void onNext(ExecuteResponse response) {
                                result.complete(new Executed(
                                        response.getIndex(),
                                        Bytes.wrap(response.getResult().toByteArray())));
                            }

                            @Override
                            public void onError(Throwable error) {
                                result.completeExceptionally(toFailure(error));
                            }

                            @Override
                            public void onCompleted() {}
                        });
        return result;
    }

    @Override
    public CompletableFuture<Executed> query(
            InetSocketAddress target, Bytes query, ReadConsistency consistency, Duration deadline) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(query, "query");
        CompletableFuture<Executed> result = new CompletableFuture<>();
        ClientServiceGrpc.newStub(channelTo(target))
                .withDeadlineAfter(deadline.toNanos(), TimeUnit.NANOSECONDS)
                .query(
                        QueryRequest.newBuilder()
                                .setQuery(UnsafeByteOperations.unsafeWrap(query.toByteArray()))
                                .setConsistency(toWire(consistency))
                                .build(),
                        new StreamObserver<>() {
                            @Override
                            public void onNext(QueryResponse response) {
                                result.complete(new Executed(
                                        response.getReadIndex(),
                                        Bytes.wrap(response.getResult().toByteArray())));
                            }

                            @Override
                            public void onError(Throwable error) {
                                result.completeExceptionally(toFailure(error));
                            }

                            @Override
                            public void onCompleted() {}
                        });
        return result;
    }

    private static dev.flotilla.wire.v1.ReadConsistency toWire(ReadConsistency consistency) {
        return switch (consistency) {
            case LINEARIZABLE -> dev.flotilla.wire.v1.ReadConsistency.READ_CONSISTENCY_LINEARIZABLE;
            case LEASE -> dev.flotilla.wire.v1.ReadConsistency.READ_CONSISTENCY_LEASE;
            case STALE -> dev.flotilla.wire.v1.ReadConsistency.READ_CONSISTENCY_STALE;
        };
    }

    @Override
    public void close() {
        channels.values().forEach(ManagedChannel::shutdownNow);
        channels.clear();
    }

    private ManagedChannel channelTo(InetSocketAddress target) {
        return channels.computeIfAbsent(
                target,
                address -> Grpc.newChannelBuilderForAddress(
                                address.getHostString(), address.getPort(), InsecureChannelCredentials.create())
                        .maxInboundMessageSize(maxMessageBytes)
                        .build());
    }

    static CallFailure toFailure(Throwable error) {
        Status status = Status.fromThrowable(error);
        Metadata trailers = Status.trailersFromThrowable(error);
        String description = String.valueOf(status.getDescription());
        @Nullable String leaderId = trailers == null ? null : trailers.get(GrpcClientService.LEADER_ID);
        @Nullable String leaderAddress = trailers == null ? null : trailers.get(GrpcClientService.LEADER_ADDRESS);
        return switch (status.getCode()) {
            case UNAVAILABLE ->
                leaderId == null && leaderAddress == null
                        ? CallFailure.of(CallFailure.Kind.UNAVAILABLE, description)
                        : CallFailure.notLeader(nodeId(leaderId), address(leaderAddress));
            case RESOURCE_EXHAUSTED -> CallFailure.of(CallFailure.Kind.OVERLOADED, description);
            case DEADLINE_EXCEEDED -> CallFailure.of(CallFailure.Kind.TIMED_OUT, description);
            case INVALID_ARGUMENT -> CallFailure.of(CallFailure.Kind.INVALID, description);
            default -> CallFailure.of(CallFailure.Kind.UNAVAILABLE, status.getCode() + ": " + description);
        };
    }

    @Nullable
    private static NodeId nodeId(@Nullable String value) {
        try {
            return value == null ? null : NodeId.of(value);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    @Nullable
    private static InetSocketAddress address(@Nullable String value) {
        if (value == null) {
            return null;
        }
        int colon = value.lastIndexOf(':');
        if (colon <= 0) {
            return null;
        }
        try {
            return new InetSocketAddress(value.substring(0, colon), Integer.parseInt(value.substring(colon + 1)));
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }
}
