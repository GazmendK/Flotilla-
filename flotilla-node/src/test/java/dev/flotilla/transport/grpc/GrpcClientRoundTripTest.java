/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.transport.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import dev.flotilla.core.Bytes;
import dev.flotilla.core.NodeId;
import dev.flotilla.transport.CallFailure;
import dev.flotilla.transport.CommandGateway;
import dev.flotilla.transport.Executed;
import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GrpcClientRoundTripTest {

    private static final Duration DEADLINE = Duration.ofSeconds(5);

    private final AtomicReference<CommandGateway> gateway = new AtomicReference<>();
    private final GrpcClientEndpoint endpoint = new GrpcClientEndpoint(16 * 1024 * 1024);

    private Server server;
    private InetSocketAddress address;

    @BeforeEach
    void start() throws Exception {
        server = NettyServerBuilder.forAddress(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
                .addService(new GrpcClientService(command -> gateway.get().execute(command)))
                .build()
                .start();
        address = (InetSocketAddress) server.getListenSockets().getFirst();
    }

    @AfterEach
    void stop() throws Exception {
        endpoint.close();
        server.shutdownNow();
        server.awaitTermination(5, TimeUnit.SECONDS);
    }

    private CallFailure failureOf(CompletableFuture<Executed> call) {
        try {
            call.get(10, TimeUnit.SECONDS);
        } catch (Exception failure) {
            return CallFailure.from(failure.getCause() == null ? failure : failure.getCause());
        }
        throw new AssertionError("expected the call to fail");
    }

    @Test
    void aResultComesBackWithItsIndex() throws Exception {
        gateway.set(command ->
                CompletableFuture.completedFuture(new Executed(7, Bytes.ofUtf8("echo:" + command.toUtf8()))));

        Executed executed =
                endpoint.execute(address, Bytes.ofUtf8("hi"), DEADLINE).get(10, TimeUnit.SECONDS);

        assertThat(executed).isEqualTo(new Executed(7, Bytes.ofUtf8("echo:hi")));
    }

    @Test
    @DisplayName("a redirect carries the leader's identity and address across the wire")
    void aRedirectCarriesTheLeader() {
        InetSocketAddress leader = new InetSocketAddress(InetAddress.getLoopbackAddress(), 9999);
        gateway.set(command -> CompletableFuture.failedFuture(CallFailure.notLeader(NodeId.of("n2"), leader)));

        CallFailure failure = failureOf(endpoint.execute(address, Bytes.ofUtf8("x"), DEADLINE));

        assertThat(failure.kind()).isEqualTo(CallFailure.Kind.NOT_LEADER);
        assertThat(failure.leader()).contains(NodeId.of("n2"));
        assertThat(failure.leaderAddress()).map(InetSocketAddress::getPort).contains(9999);
    }

    @Test
    void anElectionInProgressIsANotLeaderWithoutAHint() {
        gateway.set(command -> CompletableFuture.failedFuture(CallFailure.notLeader(null, null)));

        CallFailure failure = failureOf(endpoint.execute(address, Bytes.ofUtf8("x"), DEADLINE));

        assertThat(failure.kind())
                .as("without trailers an UNAVAILABLE reads as a plain outage, which the client treats the same way")
                .isEqualTo(CallFailure.Kind.UNAVAILABLE);
    }

    @Test
    void overloadAndInvalidKeepTheirMeaning() {
        gateway.set(command -> CompletableFuture.failedFuture(CallFailure.of(CallFailure.Kind.OVERLOADED, "busy")));
        assertThat(failureOf(endpoint.execute(address, Bytes.ofUtf8("x"), DEADLINE))
                        .kind())
                .isEqualTo(CallFailure.Kind.OVERLOADED);

        gateway.set(command -> CompletableFuture.failedFuture(CallFailure.of(CallFailure.Kind.INVALID, "garbage")));
        assertThat(failureOf(endpoint.execute(address, Bytes.ofUtf8("x"), DEADLINE))
                        .kind())
                .isEqualTo(CallFailure.Kind.INVALID);
    }

    @Test
    void anUnexpectedServerFailureIsAnOutageRatherThanACrash() {
        gateway.set(command -> CompletableFuture.failedFuture(new IllegalStateException("stopped")));

        assertThat(failureOf(endpoint.execute(address, Bytes.ofUtf8("x"), DEADLINE))
                        .kind())
                .isEqualTo(CallFailure.Kind.UNAVAILABLE);
    }

    @Test
    void aCallThatNeverAnswersTimesOut() {
        gateway.set(command -> new CompletableFuture<>());

        assertThat(failureOf(endpoint.execute(address, Bytes.ofUtf8("x"), Duration.ofMillis(200)))
                        .kind())
                .isEqualTo(CallFailure.Kind.TIMED_OUT);
    }
}
