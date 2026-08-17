/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.sim;

import dev.flotilla.core.port.RandomSource;
import java.util.List;
import java.util.SplittableRandom;

public final class DeterministicRandom {

    private final SplittableRandom random;

    public DeterministicRandom(long seed) {
        this.random = new SplittableRandom(seed);
    }

    public int nextInt(int boundExclusive) {
        return random.nextInt(boundExclusive);
    }

    public long between(long minInclusive, long maxInclusive) {
        return minInclusive + random.nextLong(maxInclusive - minInclusive + 1);
    }

    public boolean chance(double probability) {
        return random.nextDouble() < probability;
    }

    public <T> T pick(List<T> items) {
        if (items.isEmpty()) {
            throw new IllegalArgumentException("Cannot pick from an empty list");
        }
        return items.get(random.nextInt(items.size()));
    }

    public RandomSource asRandomSource() {
        return random::nextInt;
    }
}
