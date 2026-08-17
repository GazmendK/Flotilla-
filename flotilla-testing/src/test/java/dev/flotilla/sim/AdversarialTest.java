/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.sim;

import java.util.stream.LongStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class AdversarialTest {

    private static final int DEFAULT_SEEDS = 60;
    private static final int DEFAULT_TICKS = 800;

    static LongStream seeds() {
        String single = System.getProperty("flotilla.sim.seed");
        if (single != null) {
            return LongStream.of(Long.parseLong(single));
        }
        int count = Integer.getInteger("flotilla.sim.seeds", DEFAULT_SEEDS);
        long offset = Long.getLong("flotilla.sim.offset", 0L);
        return LongStream.range(0, count).map(index -> offset + index + 1);
    }

    @ParameterizedTest(name = "seed {0}")
    @MethodSource("seeds")
    @DisplayName("one entry per append and heavy churn, which is where commit-rule mistakes surface")
    void safetyHoldsWhenReplicationIsForcedToCrawl(long seed) {
        new Simulation(seed, SimConfig.adversarial(5)).run(Integer.getInteger("flotilla.sim.ticks", DEFAULT_TICKS));
    }
}
