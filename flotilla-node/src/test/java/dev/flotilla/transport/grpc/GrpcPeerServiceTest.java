/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.transport.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.flotilla.core.NodeId;
import dev.flotilla.core.message.RaftMessage;
import dev.flotilla.core.message.TimeoutNowRequest;
import dev.flotilla.wire.v1.DeliverRequest;
import dev.flotilla.wire.v1.DeliverResponse;
import dev.flotilla.wire.v1.RaftPeerServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GrpcPeerServiceTest {

    private static final NodeId SELF = NodeId.of("self");
    private static final NodeId PEER = NodeId.of("peer");
    private static final NodeId ELSEWHERE = NodeId.of("elsewhere");

    private final List<RaftMessage> received = new CopyOnWriteArrayList<>();
    private final GrpcPeerService service = new GrpcPeerService(SELF, received::add);

    private Server server;
    private ManagedChannel channel;
    private RaftPeerServiceGrpc.RaftPeerServiceBlockingStub stub;

    @BeforeEach
    void start() throws IOException {
        String name = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(name)
                .directExecutor()
                .addService(service)
                .build()
                .start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        stub = RaftPeerServiceGrpc.newBlockingStub(channel);
    }

    @AfterEach
    void stop() {
        channel.shutdownNow();
        server.shutdownNow();
    }

    private static Status.Code codeOf(Throwable failure) {
        return ((StatusRuntimeException) failure).getStatus().getCode();
    }

    @Test
    void aValidMessageIsHandedToTheNode() {
        TimeoutNowRequest message = new TimeoutNowRequest(PEER, SELF, 3);

        assertThat(stub.deliver(MessageCodec.encode(message))).isEqualTo(DeliverResponse.getDefaultInstance());

        assertThat(received).containsExactly(message);
        assertThat(service.accepted()).isEqualTo(1);
    }

    @Test
    @DisplayName("a message for another node is refused at the edge, so it can never reach the event loop")
    void aMisaddressedMessageIsRefused() {
        DeliverRequest misaddressed = MessageCodec.encode(new TimeoutNowRequest(PEER, ELSEWHERE, 3));

        assertThatThrownBy(() -> stub.deliver(misaddressed))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(failure -> assertThat(codeOf(failure)).isEqualTo(Status.Code.FAILED_PRECONDITION));

        assertThat(received).isEmpty();
        assertThat(service.refused()).isEqualTo(1);
    }

    @Test
    void aMalformedEnvelopeIsRefusedAsAnArgumentError() {
        DeliverRequest empty = DeliverRequest.newBuilder()
                .setFrom("peer")
                .setTo("self")
                .setTerm(1)
                .build();

        assertThatThrownBy(() -> stub.deliver(empty))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(failure -> assertThat(codeOf(failure)).isEqualTo(Status.Code.INVALID_ARGUMENT));

        assertThat(received).isEmpty();
    }
}
