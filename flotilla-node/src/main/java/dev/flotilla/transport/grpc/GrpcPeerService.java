/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.transport.grpc;

import dev.flotilla.core.NodeId;
import dev.flotilla.core.message.RaftMessage;
import dev.flotilla.wire.v1.DeliverRequest;
import dev.flotilla.wire.v1.DeliverResponse;
import dev.flotilla.wire.v1.RaftPeerServiceGrpc;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public final class GrpcPeerService extends RaftPeerServiceGrpc.RaftPeerServiceImplBase {

    private final NodeId self;
    private final Consumer<RaftMessage> inbound;
    private final AtomicLong accepted = new AtomicLong();
    private final AtomicLong refused = new AtomicLong();

    public GrpcPeerService(NodeId self, Consumer<RaftMessage> inbound) {
        this.self = Objects.requireNonNull(self, "self");
        this.inbound = Objects.requireNonNull(inbound, "inbound");
    }

    @Override
    public void deliver(DeliverRequest request, StreamObserver<DeliverResponse> response) {
        RaftMessage message;
        try {
            message = MessageCodec.decode(request);
        } catch (WireFormatException malformed) {
            refused.incrementAndGet();
            response.onError(Status.INVALID_ARGUMENT
                    .withDescription(malformed.getMessage())
                    .asRuntimeException());
            return;
        }
        if (!message.to().equals(self)) {
            refused.incrementAndGet();
            response.onError(Status.FAILED_PRECONDITION
                    .withDescription("A message for " + message.to() + " reached " + self)
                    .asRuntimeException());
            return;
        }
        accepted.incrementAndGet();
        inbound.accept(message);
        response.onNext(DeliverResponse.getDefaultInstance());
        response.onCompleted();
    }

    public long accepted() {
        return accepted.get();
    }

    public long refused() {
        return refused.get();
    }
}
