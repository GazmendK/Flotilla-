/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.transport.grpc;

import com.google.protobuf.ByteString;
import dev.flotilla.core.NodeId;
import dev.flotilla.core.message.InstallSnapshotRequest;
import dev.flotilla.wire.v1.DeliverRequest;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

public final class SnapshotAssembler {

    private final int maxSnapshotBytes;
    private final ConcurrentMap<NodeId, Assembly> inProgress = new ConcurrentHashMap<>();
    private final AtomicLong completed = new AtomicLong();
    private final AtomicLong abandoned = new AtomicLong();

    public SnapshotAssembler(int maxSnapshotBytes) {
        if (maxSnapshotBytes < 1) {
            throw new IllegalArgumentException("maxSnapshotBytes must be at least 1, was " + maxSnapshotBytes);
        }
        this.maxSnapshotBytes = maxSnapshotBytes;
    }

    public Optional<InstallSnapshotRequest> accept(DeliverRequest envelope) {
        dev.flotilla.wire.v1.InstallSnapshotRequest body = envelope.getInstallSnapshotRequest();
        if (SnapshotChunks.isWholeSnapshot(body)) {
            inProgress.remove(NodeId.of(envelope.getFrom()));
            return Optional.of(decode(envelope));
        }

        NodeId from = NodeId.of(envelope.getFrom());
        Assembly assembly = inProgress.compute(from, (peer, current) -> {
            if (current == null || current.index != body.getLastIncludedIndex()) {
                if (current != null) {
                    abandoned.incrementAndGet();
                }
                return new Assembly(body.getLastIncludedIndex());
            }
            return current;
        });

        synchronized (assembly) {
            assembly.add(body.getOffset(), body.getData(), body.getDone(), maxSnapshotBytes);
            if (!assembly.isComplete()) {
                return Optional.empty();
            }
            inProgress.remove(from, assembly);
            completed.incrementAndGet();
            return Optional.of(decode(envelope.toBuilder()
                    .setInstallSnapshotRequest(
                            body.toBuilder().setOffset(0).setDone(true).setData(assembly.joined()))
                    .build()));
        }
    }

    public int transfersInProgress() {
        return inProgress.size();
    }

    public long completedTransfers() {
        return completed.get();
    }

    public long abandonedTransfers() {
        return abandoned.get();
    }

    private static InstallSnapshotRequest decode(DeliverRequest envelope) {
        if (MessageCodec.decode(envelope) instanceof InstallSnapshotRequest request) {
            return request;
        }
        throw new WireFormatException("A snapshot chunk decoded into something that is not a snapshot");
    }

    private static final class Assembly {

        private final long index;
        private final NavigableMap<Long, ByteString> parts = new TreeMap<>();

        private long received;
        private long declaredTotal = -1;

        private Assembly(long index) {
            this.index = index;
        }

        private void add(long offset, ByteString data, boolean last, int limit) {
            if (offset < 0 || data.isEmpty()) {
                throw new WireFormatException("A snapshot chunk at offset " + offset + " carries " + data.size()
                        + " bytes; a chunk must carry at least one");
            }
            if (received + data.size() > limit) {
                parts.clear();
                throw new WireFormatException("A snapshot transfer exceeded the configured limit of " + limit
                        + " bytes and was refused rather than buffered");
            }
            ByteString replaced = parts.put(offset, data);
            received += data.size() - (replaced == null ? 0 : replaced.size());
            if (last) {
                declaredTotal = offset + data.size();
            }
        }

        private boolean isComplete() {
            if (declaredTotal < 0) {
                return false;
            }
            long expected = 0;
            for (Map.Entry<Long, ByteString> part : parts.entrySet()) {
                if (part.getKey() != expected) {
                    return false;
                }
                expected += part.getValue().size();
            }
            return expected == declaredTotal;
        }

        private ByteString joined() {
            ByteString joined = ByteString.EMPTY;
            for (ByteString part : parts.values()) {
                joined = joined.concat(part);
            }
            return joined;
        }
    }
}
