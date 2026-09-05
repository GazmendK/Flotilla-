/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.server;

import java.time.Duration;
import java.util.Objects;

public record ServerConfig(Duration tickInterval, int eventQueueCapacity, Duration shutdownTimeout) {

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
    }

    public static ServerConfig defaults() {
        return new ServerConfig(Duration.ofMillis(50), 2048, Duration.ofSeconds(5));
    }

    public ServerConfig withTickInterval(Duration interval) {
        return new ServerConfig(interval, eventQueueCapacity, shutdownTimeout);
    }

    public ServerConfig withEventQueueCapacity(int capacity) {
        return new ServerConfig(tickInterval, capacity, shutdownTimeout);
    }
}
