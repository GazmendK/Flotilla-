/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.transport.grpc;

import dev.flotilla.core.ClusterConfig;
import dev.flotilla.core.ConfChange;
import dev.flotilla.core.NodeId;
import dev.flotilla.transport.AdminGateway;
import dev.flotilla.transport.CallFailure;
import dev.flotilla.transport.ClusterStatus;
import dev.flotilla.wire.v1.AdminServiceGrpc;
import dev.flotilla.wire.v1.ChangeMembershipRequest;
import dev.flotilla.wire.v1.ChangeMembershipResponse;
import dev.flotilla.wire.v1.DescribeClusterRequest;
import dev.flotilla.wire.v1.DescribeClusterResponse;
import dev.flotilla.wire.v1.TransferLeadershipRequest;
import dev.flotilla.wire.v1.TransferLeadershipResponse;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

public final class GrpcAdminService extends AdminServiceGrpc.AdminServiceImplBase {

    private final AdminGateway gateway;

    public GrpcAdminService(AdminGateway gateway) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
    }

    @Override
    public void changeMembership(ChangeMembershipRequest request, StreamObserver<ChangeMembershipResponse> response) {
        ConfChange change;
        try {
            change = AdminCodec.decode(request);
        } catch (WireFormatException malformed) {
            response.onError(invalid(malformed));
            return;
        }
        answer(
                gateway.changeMembership(change),
                response,
                (ClusterConfig config) -> ChangeMembershipResponse.newBuilder()
                        .setConfiguration(AdminCodec.encode(config))
                        .build());
    }

    @Override
    public void transferLeadership(
            TransferLeadershipRequest request, StreamObserver<TransferLeadershipResponse> response) {
        NodeId target;
        try {
            target = AdminCodec.node(request.getTarget());
        } catch (WireFormatException malformed) {
            response.onError(invalid(malformed));
            return;
        }
        answer(
                gateway.transferLeadership(target),
                response,
                (NodeId leader) -> TransferLeadershipResponse.newBuilder()
                        .setLeader(leader.value())
                        .build());
    }

    @Override
    public void describeCluster(DescribeClusterRequest request, StreamObserver<DescribeClusterResponse> response) {
        answer(
                gateway.describeCluster(request.getLeaderOnly()),
                response,
                (ClusterStatus status) -> AdminCodec.encode(status));
    }

    private static <T, R> void answer(CompletableFuture<T> outcome, StreamObserver<R> response, Function<T, R> encode) {
        CompletableFuture<T> _ = outcome.whenComplete((value, failure) -> {
            if (failure != null) {
                response.onError(GrpcClientService.toStatus(CallFailure.from(failure)));
                return;
            }
            response.onNext(encode.apply(value));
            response.onCompleted();
        });
    }

    private static RuntimeException invalid(WireFormatException malformed) {
        return Status.INVALID_ARGUMENT.withDescription(malformed.getMessage()).asRuntimeException();
    }
}
