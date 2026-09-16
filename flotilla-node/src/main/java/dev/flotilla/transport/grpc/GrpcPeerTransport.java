/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.transport.grpc;

import dev.flotilla.core.NodeId;
import dev.flotilla.core.message.InstallSnapshotRequest;
import dev.flotilla.core.message.RaftMessage;
import dev.flotilla.transport.PeerDirectory;
import dev.flotilla.transport.TransportConfig;
import dev.flotilla.wire.v1.DeliverRequest;
import dev.flotilla.wire.v1.DeliverResponse;
import dev.flotilla.wire.v1.RaftPeerServiceGrpc;
import io.grpc.ConnectivityState;
import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.ManagedChannel;
import io.grpc.stub.StreamObserver;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
    private final AtomicLong backoffResets = new AtomicLong();
    private final AtomicLong chunksSent = new AtomicLong();
    private final ExecutorService senders;

    private volatile boolean closed;

    public GrpcPeerTransport(NodeId self, PeerDirectory directory, TransportConfig config) {
        this.self = Objects.requireNonNull(self, "self");
        this.directory = Objects.requireNonNull(directory, "directory");
        this.config = Objects.requireNonNull(config, "config");
        this.senders = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "flotilla-" + self + "-snapshot-send");
            thread.setDaemon(true);
            return thread;
        });
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
        if (message instanceof InstallSnapshotRequest snapshot) {
            streamSnapshot(peer, snapshot);
            return;
        }
        if (!peer.tryAcquire(config.maxInflightPerPeer())) {
            dropped.incrementAndGet();
            return;
        }
        peer.stub()
                .withDeadlineAfter(config.deliverDeadline().toNanos(), TimeUnit.NANOSECONDS)
                .deliver(MessageCodec.encode(message), new Completion(peer));
    }

    private void streamSnapshot(Peer peer, InstallSnapshotRequest snapshot) {
        if (!peer.beginSnapshot()) {
            dropped.incrementAndGet();
            return;
        }
        List<DeliverRequest> chunks = SnapshotChunks.split(snapshot, config.snapshotChunkBytes());
        senders.execute(() -> {
            try {
                for (DeliverRequest chunk : chunks) {
                    peer.blocking()
                            .withDeadlineAfter(config.snapshotChunkDeadline().toNanos(), TimeUnit.NANOSECONDS)
                            .deliver(chunk);
                    chunksSent.incrementAndGet();
                }
                delivered.incrementAndGet();
            } catch (RuntimeException failure) {
                failed.incrementAndGet();
            } finally {
                peer.endSnapshot();
            }
        });
    }

    public void peerIsAlive(NodeId node) {
        Peer peer = peers.get(node);
        if (peer != null && peer.reconnectIfBackingOff()) {
            backoffResets.incrementAndGet();
        }
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

    public long backoffResets() {
        return backoffResets.get();
    }

    public long snapshotChunksSent() {
        return chunksSent.get();
    }

    public int inflight(NodeId node) {
        Peer peer = peers.get(node);
        return peer == null ? 0 : peer.inflight();
    }

    @Override
    public void close() {
        closed = true;
        senders.shutdownNow();
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
        private final RaftPeerServiceGrpc.RaftPeerServiceBlockingStub blockingStub;
        private final AtomicInteger inflight = new AtomicInteger();
        private final java.util.concurrent.atomic.AtomicBoolean snapshotInFlight =
                new java.util.concurrent.atomic.AtomicBoolean();

        Peer(InetSocketAddress address, ManagedChannel channel) {
            this.address = address;
            this.channel = channel;
            this.stub = RaftPeerServiceGrpc.newStub(channel);
            this.blockingStub = RaftPeerServiceGrpc.newBlockingStub(channel);
        }

        InetSocketAddress address() {
            return address;
        }

        RaftPeerServiceGrpc.RaftPeerServiceStub stub() {
            return stub;
        }

        RaftPeerServiceGrpc.RaftPeerServiceBlockingStub blocking() {
            return blockingStub;
        }

        boolean beginSnapshot() {
            return snapshotInFlight.compareAndSet(false, true);
        }

        void endSnapshot() {
            snapshotInFlight.set(false);
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

        boolean reconnectIfBackingOff() {
            if (channel.getState(false) != ConnectivityState.TRANSIENT_FAILURE) {
                return false;
            }
            channel.resetConnectBackoff();
            return true;
        }

        void shutdown() {
            channel.shutdownNow();
        }
    }
}
