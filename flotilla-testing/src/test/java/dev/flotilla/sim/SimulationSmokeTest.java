/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.sim;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SimulationSmokeTest {

    @Test
    @DisplayName("a calm cluster elects a leader and commits what it is given")
    void aCalmClusterMakesProgress() {
        Simulation simulation = new Simulation(1, SimConfig.calm(5));

        simulation.run(300);

        assertThat(simulation.leaders()).hasSize(1);
        assertThat(simulation.highestCommitIndex()).isPositive();
        assertThat(simulation.appliedEntryCount()).isPositive();
    }

    @Test
    @DisplayName("simulated time runs far ahead of wall-clock time, which is the whole point")
    void simulatedTimeIsCheap() {
        Simulation simulation = new Simulation(2, SimConfig.calm(5));

        simulation.run(2000);

        assertThat(simulation.time()).isEqualTo(2000L * VirtualClock.UNITS_PER_TICK);
    }

    @Test
    @DisplayName("a single node cluster commits without anyone to talk to")
    void aSingleNodeClusterCommits() {
        Simulation simulation = new Simulation(3, SimConfig.calm(1));

        simulation.run(100);

        assertThat(simulation.leaders()).hasSize(1);
        assertThat(simulation.highestCommitIndex()).isPositive();
    }

    @Test
    @DisplayName("the trace records the role changes that explain a run")
    void theTraceExplainsWhatHappened() {
        Simulation simulation = new Simulation(4, SimConfig.calm(3));

        simulation.run(100);

        assertThat(simulation.trace().render()).contains("LEADER");
    }
}
