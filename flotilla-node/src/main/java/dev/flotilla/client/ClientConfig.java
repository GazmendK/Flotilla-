/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.client;

import java.time.Duration;
import java.util.Objects;

public record ClientConfig(
        Duration attemptDeadline, int maxAttempts, Duration backoffBase, Duration backoffMax, int maxMessageBytes) {

    public ClientConfig {
        Objects.requireNonNull(attemptDeadline, "attemptDeadline");
        Objects.requireNonNull(backoffBase, "backoffBase");
        Objects.requireNonNull(backoffMax, "backoffMax");
        if (attemptDeadline.isNegative() || attemptDeadline.isZero()) {
            throw new IllegalArgumentException("attemptDeadline must be positive, was " + attemptDeadline);
        }
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1, was " + maxAttempts);
        }
        if (backoffBase.isNegative() || backoffBase.isZero()) {
            throw new IllegalArgumentException("backoffBase must be positive, was " + backoffBase);
        }
        if (backoffMax.compareTo(backoffBase) < 0) {
            throw new IllegalArgumentException(
                    "backoffMax (" + backoffMax + ") must not be shorter than backoffBase (" + backoffBase + ")");
        }
        if (maxMessageBytes < 1024) {
            throw new IllegalArgumentException("maxMessageBytes must be at least 1 KiB, was " + maxMessageBytes);
        }
    }

    public static ClientConfig defaults() {
        return new ClientConfig(
                Duration.ofSeconds(2), 10, Duration.ofMillis(50), Duration.ofSeconds(2), 16 * 1024 * 1024);
    }

    public ClientConfig withAttemptDeadline(Duration deadline) {
        return new ClientConfig(deadline, maxAttempts, backoffBase, backoffMax, maxMessageBytes);
    }

    public ClientConfig withMaxAttempts(int attempts) {
        return new ClientConfig(attemptDeadline, attempts, backoffBase, backoffMax, maxMessageBytes);
    }

    public ClientConfig withBackoff(Duration base, Duration max) {
        return new ClientConfig(attemptDeadline, maxAttempts, base, max, maxMessageBytes);
    }
}
