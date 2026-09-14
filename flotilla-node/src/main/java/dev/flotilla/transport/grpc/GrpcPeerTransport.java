/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.transport.grpc;

import dev.flotilla.core.NodeId;
import dev.flotilla.core.message.RaftMessage;
import dev.flotilla.transport.PeerDirectory;
import dev.flotilla.transport.TransportConfig;
import dev.flotilla.wire.v1.DeliverResponse;
import dev.flotilla.wire.v1.RaftPeerServiceGrpc;
import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.ManagedChannel;
import io.grpc.stub.StreamObserver;
import java.net.InetSocketAddress;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class GrpcPeerTransport implements AutoCloseable {

    private final NodeId self;
    private final PeerDirectory directory;
    private final TransportConfig config;
    private final ConcurrentMap<NodeId, Peer> peers = new ConcurrentHashMap<>();
    private final AtomicLong delivered = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();

    private volatile boolean closed;

    public GrpcPeerTransport(NodeId self, PeerDirectory directory, TransportConfig config) {
        this.self = Objects.requireNonNull(self, "self");
        this.directory = Objects.requireNonNull(directory, "directory");
        this.config = Objects.requireNonNull(config, "config");
    }

    public void send(RaftMessage message) {
        Objects.requireNonNull(message, "message");
        if (closed || message.to().equals(self)) {
            dropped.incrementAndGet();
            return;
        }
        Optional<InetSocketAddress> address = directory.addressOf(message.to());
        if (address.isEmpty()) {
            dropped.incrementAndGet();
            return;
        }
        Peer peer = peerAt(message.to(), address.get());
        if (!peer.tryAcquire(config.maxInflightPerPeer())) {
            dropped.incrementAndGet();
            return;
        }
        peer.stub()
                .withDeadlineAfter(config.deliverDeadline().toNanos(), TimeUnit.NANOSECONDS)
                .deliver(MessageCodec.encode(message), new Completion(peer));
    }

    public long delivered() {
        return delivered.get();
    }

    public long dropped() {
        return dropped.get();
    }

    public long failed() {
        return failed.get();
    }

    public int inflight(NodeId node) {
        Peer peer = peers.get(node);
        return peer == null ? 0 : peer.inflight();
    }

    @Override
    public void close() {
        closed = true;
        for (Peer peer : peers.values()) {
            peer.shutdown();
        }
        peers.clear();
    }

    private Peer peerAt(NodeId node, InetSocketAddress address) {
        Peer current = peers.get(node);
        if (current != null && current.address().equals(address)) {
            return current;
        }
        Peer replacement = new Peer(address, open(address));
        Peer previous = peers.put(node, replacement);
        if (previous != null) {
            previous.shutdown();
        }
        return replacement;
    }

    private ManagedChannel open(InetSocketAddress address) {
        return Grpc.newChannelBuilderForAddress(
                        address.getHostString(), address.getPort(), InsecureChannelCredentials.create())
                .maxInboundMessageSize(config.maxMessageBytes())
                .keepAliveTime(config.keepAliveTime().toNanos(), TimeUnit.NANOSECONDS)
                .keepAliveWithoutCalls(true)
                .build();
    }

    private final class Completion implements StreamObserver<DeliverResponse> {

        private final Peer peer;

        Completion(Peer peer) {
            this.peer = peer;
        }

        @Override
        public void onNext(DeliverResponse response) {}

        @Override
        public void onError(Throwable error) {
            peer.release();
            failed.incrementAndGet();
        }

        @Override
        public void onCompleted() {
            peer.release();
            delivered.incrementAndGet();
        }
    }

    private static final class Peer {

        private final InetSocketAddress address;
        private final ManagedChannel channel;
        private final RaftPeerServiceGrpc.RaftPeerServiceStub stub;
        private final AtomicInteger inflight = new AtomicInteger();

        Peer(InetSocketAddress address, ManagedChannel channel) {
            this.address = address;
            this.channel = channel;
            this.stub = RaftPeerServiceGrpc.newStub(channel);
        }

        InetSocketAddress address() {
            return address;
        }

        RaftPeerServiceGrpc.RaftPeerServiceStub stub() {
            return stub;
        }

        int inflight() {
            return inflight.get();
        }

        boolean tryAcquire(int limit) {
            while (true) {
                int current = inflight.get();
                if (current >= limit) {
                    return false;
                }
                if (inflight.compareAndSet(current, current + 1)) {
                    return true;
                }
            }
        }

        void release() {
            inflight.decrementAndGet();
        }

        void shutdown() {
            channel.shutdownNow();
        }
    }
}
