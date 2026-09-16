/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.sim;

import java.util.Objects;

public record WorkloadConfig(int clients, double operationProbability, int keys, ReadMode readMode, int timeoutTicks) {

    public enum ReadMode {
        LINEARIZABLE,
        LEASE,
        UNSAFE_LOCAL
    }

    public WorkloadConfig {
        Objects.requireNonNull(readMode, "readMode");
        if (clients < 0) {
            throw new IllegalArgumentException("clients must not be negative, was " + clients);
        }
        if (keys < 1) {
            throw new IllegalArgumentException("keys must be at least 1, was " + keys);
        }
        if (timeoutTicks < 1) {
            throw new IllegalArgumentException("timeoutTicks must be at least 1, was " + timeoutTicks);
        }
    }

    public static WorkloadConfig none() {
        return new WorkloadConfig(0, 0.0, 1, ReadMode.LINEARIZABLE, 1);
    }

    public static WorkloadConfig clients(int clients, ReadMode readMode) {
        return new WorkloadConfig(clients, 0.3, 4, readMode, 20);
    }

    public static ReadMode readModeFromSystemProperty(ReadMode fallback) {
        String configured = System.getProperty("flotilla.read.mode");
        return configured == null ? fallback : ReadMode.valueOf(configured);
    }
}
