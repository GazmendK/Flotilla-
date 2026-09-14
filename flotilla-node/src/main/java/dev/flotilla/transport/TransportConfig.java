/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.transport;

import java.time.Duration;
import java.util.Objects;

public record TransportConfig(
        Duration deliverDeadline, int maxInflightPerPeer, int maxMessageBytes, Duration keepAliveTime) {

    public static final int DEFAULT_MAX_MESSAGE_BYTES = 16 * 1024 * 1024;
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
    }

    public static TransportConfig defaults() {
        return new TransportConfig(Duration.ofMillis(200), 64, DEFAULT_MAX_MESSAGE_BYTES, Duration.ofSeconds(30));
    }

    public TransportConfig withDeliverDeadline(Duration deadline) {
        return new TransportConfig(deadline, maxInflightPerPeer, maxMessageBytes, keepAliveTime);
    }

    public TransportConfig withMaxInflightPerPeer(int inflight) {
        return new TransportConfig(deliverDeadline, inflight, maxMessageBytes, keepAliveTime);
    }

    public TransportConfig withMaxMessageBytes(int bytes) {
        return new TransportConfig(deliverDeadline, maxInflightPerPeer, bytes, keepAliveTime);
    }
}
