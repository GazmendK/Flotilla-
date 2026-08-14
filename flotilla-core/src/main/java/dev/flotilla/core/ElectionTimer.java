/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.core;

import dev.flotilla.core.port.RandomSource;
import java.util.Objects;

@RaftSpec("§5.2 Leader election")
public final class ElectionTimer {

    private final RaftConfig config;
    private final RandomSource random;

    private int elapsedTicks;
    private int timeoutTicks;

    public ElectionTimer(RaftConfig config, RandomSource random) {
        this.config = Objects.requireNonNull(config, "config");
        this.random = Objects.requireNonNull(random, "random");
        reset();
    }

    public void tick() {
        elapsedTicks++;
    }

    public boolean hasExpired() {
        return elapsedTicks >= timeoutTicks;
    }

    public void reset() {
        elapsedTicks = 0;
        timeoutTicks = config.electionTimeoutMinTicks() + random.nextInt(config.electionTimeoutSpreadTicks());
    }

    public int elapsedTicks() {
        return elapsedTicks;
    }

    public int timeoutTicks() {
        return timeoutTicks;
    }
}
