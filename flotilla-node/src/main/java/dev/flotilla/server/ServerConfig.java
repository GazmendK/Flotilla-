/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.server;

import java.time.Duration;
import java.util.Objects;

public record ServerConfig(
        Duration tickInterval,
        int eventQueueCapacity,
        int maxBatchSize,
        int applyQueueCapacity,
        Duration shutdownTimeout) {

    public ServerConfig {
        Objects.requireNonNull(tickInterval, "tickInterval");
        Objects.requireNonNull(shutdownTimeout, "shutdownTimeout");
        if (tickInterval.isNegative() || tickInterval.isZero()) {
            throw new IllegalArgumentException("tickInterval must be positive, was " + tickInterval);
        }
        if (eventQueueCapacity < 1) {
            throw new IllegalArgumentException("eventQueueCapacity must be at least 1, was " + eventQueueCapacity
                    + "; an unbounded queue trades memory for time and dies under load instead of rejecting");
        }
        if (maxBatchSize < 1) {
            throw new IllegalArgumentException("maxBatchSize must be at least 1, was " + maxBatchSize);
        }
        if (applyQueueCapacity < 1) {
            throw new IllegalArgumentException("applyQueueCapacity must be at least 1, was " + applyQueueCapacity);
        }
    }

    public static ServerConfig defaults() {
        return new ServerConfig(Duration.ofMillis(50), 2048, 64, 1024, Duration.ofSeconds(5));
    }

    public ServerConfig withTickInterval(Duration interval) {
        return new ServerConfig(interval, eventQueueCapacity, maxBatchSize, applyQueueCapacity, shutdownTimeout);
    }

    public ServerConfig withEventQueueCapacity(int capacity) {
        return new ServerConfig(tickInterval, capacity, maxBatchSize, applyQueueCapacity, shutdownTimeout);
    }

    public ServerConfig withMaxBatchSize(int size) {
        return new ServerConfig(tickInterval, eventQueueCapacity, size, applyQueueCapacity, shutdownTimeout);
    }

    public ServerConfig withApplyQueueCapacity(int capacity) {
        return new ServerConfig(tickInterval, eventQueueCapacity, maxBatchSize, capacity, shutdownTimeout);
    }

    public ServerConfig withShutdownTimeout(Duration timeout) {
        return new ServerConfig(tickInterval, eventQueueCapacity, maxBatchSize, applyQueueCapacity, timeout);
    }
}
