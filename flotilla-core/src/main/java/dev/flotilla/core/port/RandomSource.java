/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.core.port;

import java.util.SplittableRandom;

@FunctionalInterface
public interface RandomSource {
    int nextInt(int boundExclusive);

    static RandomSource seeded(long seed) {
        SplittableRandom random = new SplittableRandom(seed);
        return random::nextInt;
    }
}
