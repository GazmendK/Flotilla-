/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.sim;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.LongStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class RandomizedRaftTest {

    private static final int DEFAULT_SEEDS = 120;
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

    private static int ticks() {
        return Integer.getInteger("flotilla.sim.ticks", DEFAULT_TICKS);
    }

    @ParameterizedTest(name = "seed {0}")
    @MethodSource("seeds")
    @DisplayName("every safety property holds under crashes, partitions, drops, duplicates and reordering")
    void safetyHoldsUnderChaos(long seed) {
        new Simulation(seed, SimConfig.chaotic(5)).run(ticks());
    }

    @ParameterizedTest(name = "seed {0}")
    @MethodSource("seeds")
    @DisplayName("a three node cluster is checked too, because its quorum arithmetic differs")
    void safetyHoldsForThreeNodes(long seed) {
        new Simulation(seed, SimConfig.chaotic(3)).run(ticks() / 2);
    }

    @ParameterizedTest(name = "seed {0}")
    @MethodSource("seeds")
    @DisplayName("progress resumes once the faults stop, so safety is not achieved by doing nothing")
    void livenessReturnsAfterTheChaosStops(long seed) {
        Simulation simulation = new Simulation(seed, SimConfig.chaotic(5));
        simulation.run(400);

        simulation.heal();
        simulation.restartAll();
        simulation.runWithoutFaults(400);

        assertThat(simulation.leaders())
                .as("a healed cluster with every node running must elect a leader")
                .hasSize(1);
        assertThat(simulation.highestCommitIndex())
                .as("and it must be able to commit")
                .isPositive();
    }
}
