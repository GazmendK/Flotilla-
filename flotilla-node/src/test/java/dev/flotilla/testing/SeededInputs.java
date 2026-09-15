/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.testing;

import java.util.SplittableRandom;

public final class SeededInputs {

    public static final long SEED = 20260915L;

    private SeededInputs() {}

    public static SplittableRandom random() {
        return new SplittableRandom(SEED);
    }

    public static byte[] bytes(SplittableRandom random, int maxLength) {
        int length =
                switch (random.nextInt(8)) {
                    case 0 -> 0;
                    case 1 -> 1;
                    case 2 -> maxLength;
                    default -> random.nextInt(maxLength + 1);
                };
        byte[] bytes = new byte[length];
        random.nextBytes(bytes);
        return bytes;
    }

    public static long between(SplittableRandom random, long minInclusive, long maxInclusive) {
        return random.nextLong(minInclusive, maxInclusive + 1);
    }
}
