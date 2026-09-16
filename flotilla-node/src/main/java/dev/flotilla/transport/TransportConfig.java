/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.transport;

import java.time.Duration;
import java.util.Objects;

public record TransportConfig(
        Duration deliverDeadline,
        int maxInflightPerPeer,
        int maxMessageBytes,
        Duration keepAliveTime,
        int snapshotChunkBytes,
        int maxSnapshotBytes,
        Duration snapshotChunkDeadline) {

    public static final int DEFAULT_MAX_MESSAGE_BYTES = 16 * 1024 * 1024;
    public static final int DEFAULT_SNAPSHOT_CHUNK_BYTES = 1024 * 1024;
    public static final int DEFAULT_MAX_SNAPSHOT_BYTES = 256 * 1024 * 1024;
    public static final Duration MIN_KEEP_ALIVE = Duration.ofSeconds(10);

    public TransportConfig {
        Objects.requireNonNull(deliverDeadline, "deliverDeadline");
        Objects.requireNonNull(keepAliveTime, "keepAliveTime");
        if (deliverDeadline.isNegative() || deliverDeadline.isZero()) {
            throw new IllegalArgumentException("deliverDeadline must be positive, was " + deliverDeadline);
        }
        if (maxInflightPerPeer < 1) {
            throw new IllegalArgumentException("maxInflightPerPeer must be at least 1, was " + maxInflightPerPeer
                    + "; with no slot at all nothing could ever be sent");
        }
        if (maxMessageBytes < 64 * 1024) {
            throw new IllegalArgumentException("maxMessageBytes must be at least 64 KiB, was " + maxMessageBytes);
        }
        if (keepAliveTime.compareTo(MIN_KEEP_ALIVE) < 0) {
            throw new IllegalArgumentException("keepAliveTime must be at least " + MIN_KEEP_ALIVE + ", was "
                    + keepAliveTime + "; gRPC silently raises anything lower, and the server's permitted "
                    + "ping interval is derived from this value");
        }
        if (snapshotChunkBytes < 1024 || snapshotChunkBytes > maxMessageBytes / 2) {
            throw new IllegalArgumentException("snapshotChunkBytes must be between 1 KiB and half of "
                    + "maxMessageBytes (" + maxMessageBytes / 2 + "), was " + snapshotChunkBytes
                    + "; the other half is the cluster configuration and the framing that travel with every chunk");
        }
        Objects.requireNonNull(snapshotChunkDeadline, "snapshotChunkDeadline");
        if (snapshotChunkDeadline.isNegative() || snapshotChunkDeadline.isZero()) {
            throw new IllegalArgumentException("snapshotChunkDeadline must be positive, was " + snapshotChunkDeadline);
        }
        if (maxSnapshotBytes < snapshotChunkBytes) {
            throw new IllegalArgumentException("maxSnapshotBytes (" + maxSnapshotBytes
                    + ") must be at least snapshotChunkBytes (" + snapshotChunkBytes + ")");
        }
    }

    public static TransportConfig defaults() {
        return new TransportConfig(
                Duration.ofMillis(200),
                64,
                DEFAULT_MAX_MESSAGE_BYTES,
                Duration.ofSeconds(30),
                DEFAULT_SNAPSHOT_CHUNK_BYTES,
                DEFAULT_MAX_SNAPSHOT_BYTES,
                Duration.ofSeconds(30));
    }

    public TransportConfig withDeliverDeadline(Duration deadline) {
        return new TransportConfig(
                deadline,
                maxInflightPerPeer,
                maxMessageBytes,
                keepAliveTime,
                snapshotChunkBytes,
                maxSnapshotBytes,
                snapshotChunkDeadline);
    }

    public TransportConfig withMaxInflightPerPeer(int inflight) {
        return new TransportConfig(
                deliverDeadline,
                inflight,
                maxMessageBytes,
                keepAliveTime,
                snapshotChunkBytes,
                maxSnapshotBytes,
                snapshotChunkDeadline);
    }

    public TransportConfig withMaxMessageBytes(int bytes) {
        return new TransportConfig(
                deliverDeadline,
                maxInflightPerPeer,
                bytes,
                keepAliveTime,
                snapshotChunkBytes,
                maxSnapshotBytes,
                snapshotChunkDeadline);
    }

    public TransportConfig withSnapshotChunkBytes(int bytes) {
        return new TransportConfig(
                deliverDeadline,
                maxInflightPerPeer,
                maxMessageBytes,
                keepAliveTime,
                bytes,
                maxSnapshotBytes,
                snapshotChunkDeadline);
    }

    public TransportConfig withMaxSnapshotBytes(int bytes) {
        return new TransportConfig(
                deliverDeadline,
                maxInflightPerPeer,
                maxMessageBytes,
                keepAliveTime,
                snapshotChunkBytes,
                bytes,
                snapshotChunkDeadline);
    }

    public TransportConfig withSnapshotChunkDeadline(Duration deadline) {
        return new TransportConfig(
                deliverDeadline,
                maxInflightPerPeer,
                maxMessageBytes,
                keepAliveTime,
                snapshotChunkBytes,
                maxSnapshotBytes,
                deadline);
    }
}
