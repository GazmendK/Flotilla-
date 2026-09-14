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
import dev.flotilla.wire.v1.ClientServiceGrpc;
import dev.flotilla.wire.v1.ExecuteRequest;
import dev.flotilla.wire.v1.ExecuteResponse;
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

    public GrpcClientService(CommandGateway gateway) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
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

    private static RuntimeException toStatus(CallFailure failure) {
        Status status =
                switch (failure.kind()) {
                    case NOT_LEADER, UNAVAILABLE, TIMED_OUT -> Status.UNAVAILABLE;
                    case OVERLOADED -> Status.RESOURCE_EXHAUSTED;
                    case INVALID -> Status.INVALID_ARGUMENT;
                };
        Metadata trailers = new Metadata();
        failure.leader().ifPresent(leader -> trailers.put(LEADER_ID, leader.value()));
        failure.leaderAddress()
                .ifPresent(address -> trailers.put(LEADER_ADDRESS, address.getHostString() + ":" + address.getPort()));
        return status.withDescription(failure.getMessage()).asRuntimeException(trailers);
    }
}
