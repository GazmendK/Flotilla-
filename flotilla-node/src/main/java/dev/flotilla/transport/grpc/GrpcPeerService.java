/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.transport.grpc;

import dev.flotilla.core.NodeId;
import dev.flotilla.core.message.InstallSnapshotRequest;
import dev.flotilla.core.message.RaftMessage;
import dev.flotilla.wire.v1.DeliverRequest;
import dev.flotilla.wire.v1.DeliverResponse;
import dev.flotilla.wire.v1.RaftPeerServiceGrpc;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public final class GrpcPeerService extends RaftPeerServiceGrpc.RaftPeerServiceImplBase {

    private final NodeId self;
    private final Consumer<RaftMessage> inbound;
    private final SnapshotAssembler snapshots;
    private final AtomicLong accepted = new AtomicLong();
    private final AtomicLong refused = new AtomicLong();

    public GrpcPeerService(NodeId self, Consumer<RaftMessage> inbound, int maxSnapshotBytes) {
        this.self = Objects.requireNonNull(self, "self");
        this.inbound = Objects.requireNonNull(inbound, "inbound");
        this.snapshots = new SnapshotAssembler(maxSnapshotBytes);
    }

    @Override
    public void deliver(DeliverRequest request, StreamObserver<DeliverResponse> response) {
        RaftMessage message;
        try {
            if (SnapshotChunks.isSnapshotChunk(request)) {
                acceptChunk(request, response);
                return;
            }
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

    private void acceptChunk(DeliverRequest request, StreamObserver<DeliverResponse> response) {
        if (!request.getTo().equals(self.value())) {
            refused.incrementAndGet();
            response.onError(Status.FAILED_PRECONDITION
                    .withDescription("A snapshot for " + request.getTo() + " reached " + self)
                    .asRuntimeException());
            return;
        }
        Optional<InstallSnapshotRequest> assembled = snapshots.accept(request);
        accepted.incrementAndGet();
        response.onNext(DeliverResponse.getDefaultInstance());
        response.onCompleted();
        assembled.ifPresent(inbound);
    }

    public long snapshotTransfersInProgress() {
        return snapshots.transfersInProgress();
    }

    public long snapshotTransfersCompleted() {
        return snapshots.completedTransfers();
    }

    public long snapshotTransfersAbandoned() {
        return snapshots.abandonedTransfers();
    }

    public long accepted() {
        return accepted.get();
    }

    public long refused() {
        return refused.get();
    }
}
