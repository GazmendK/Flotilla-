/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.transport.grpc;

import com.google.protobuf.UnsafeByteOperations;
import dev.flotilla.core.Bytes;
import dev.flotilla.transport.CallFailure;
import dev.flotilla.transport.CommandGateway;
import dev.flotilla.transport.Executed;
import dev.flotilla.transport.QueryGateway;
import dev.flotilla.transport.ReadConsistency;
import dev.flotilla.wire.v1.ClientServiceGrpc;
import dev.flotilla.wire.v1.ExecuteRequest;
import dev.flotilla.wire.v1.ExecuteResponse;
import dev.flotilla.wire.v1.QueryRequest;
import dev.flotilla.wire.v1.QueryResponse;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

public final class GrpcClientService extends ClientServiceGrpc.ClientServiceImplBase {

    static final Metadata.Key<String> LEADER_ID =
            Metadata.Key.of("flotilla-leader-id", Metadata.ASCII_STRING_MARSHALLER);
    static final Metadata.Key<String> LEADER_ADDRESS =
            Metadata.Key.of("flotilla-leader-address", Metadata.ASCII_STRING_MARSHALLER);

    private final CommandGateway gateway;
    private final QueryGateway queries;

    public GrpcClientService(CommandGateway gateway) {
        this(gateway, QueryGateway.unsupported());
    }

    public GrpcClientService(CommandGateway gateway, QueryGateway queries) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.queries = Objects.requireNonNull(queries, "queries");
    }

    @Override
    public void query(QueryRequest request, StreamObserver<QueryResponse> response) {
        ReadConsistency consistency =
                switch (request.getConsistency()) {
                    case READ_CONSISTENCY_LEASE -> ReadConsistency.LEASE;
                    case READ_CONSISTENCY_STALE -> ReadConsistency.STALE;
                    case READ_CONSISTENCY_LINEARIZABLE, READ_CONSISTENCY_UNSPECIFIED, UNRECOGNIZED ->
                        ReadConsistency.LINEARIZABLE;
                };
        Bytes query = Bytes.wrap(request.getQuery().toByteArray());
        CompletableFuture<Executed> _ = queries.query(query, consistency).whenComplete((answered, failure) -> {
            if (failure != null) {
                response.onError(toStatus(CallFailure.from(failure)));
                return;
            }
            response.onNext(QueryResponse.newBuilder()
                    .setReadIndex(answered.index())
                    .setResult(UnsafeByteOperations.unsafeWrap(answered.result().toByteArray()))
                    .build());
            response.onCompleted();
        });
    }

    @Override
    public void execute(ExecuteRequest request, StreamObserver<ExecuteResponse> response) {
        Bytes command = Bytes.wrap(request.getCommand().toByteArray());
        CompletableFuture<Executed> _ = gateway.execute(command).whenComplete((executed, failure) -> {
            if (failure != null) {
                response.onError(toStatus(CallFailure.from(failure)));
                return;
            }
            response.onNext(ExecuteResponse.newBuilder()
                    .setIndex(executed.index())
                    .setResult(UnsafeByteOperations.unsafeWrap(executed.result().toByteArray()))
                    .build());
            response.onCompleted();
        });
    }

    static RuntimeException toStatus(CallFailure failure) {
        Status status =
                switch (failure.kind()) {
                    case NOT_LEADER, UNAVAILABLE, TIMED_OUT -> Status.UNAVAILABLE;
                    case OVERLOADED -> Status.RESOURCE_EXHAUSTED;
                    case INVALID -> Status.INVALID_ARGUMENT;
                    case REJECTED -> Status.FAILED_PRECONDITION;
                };
        Metadata trailers = new Metadata();
        failure.leader().ifPresent(leader -> trailers.put(LEADER_ID, leader.value()));
        failure.leaderAddress()
                .ifPresent(address -> trailers.put(LEADER_ADDRESS, address.getHostString() + ":" + address.getPort()));
        return status.withDescription(failure.getMessage()).asRuntimeException(trailers);
    }
}
