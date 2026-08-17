/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.sim;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DeterminismTest {

    @ParameterizedTest(name = "seed {0} replays identically")
    @ValueSource(longs = {1, 7, 12345, 8134729})
    @DisplayName("the same seed produces the same run, which is what makes a failure reproducible")
    void sameSeedSameRun(long seed) {
        Simulation first = new Simulation(seed, SimConfig.chaotic(5));
        Simulation second = new Simulation(seed, SimConfig.chaotic(5));

        first.run(400);
        second.run(400);

        assertThat(second.digest()).isEqualTo(first.digest());
        assertThat(second.trace().lines()).isEqualTo(first.trace().lines());
    }

    @Test
    @DisplayName("different seeds explore different runs, or the suite would only ever test one story")
    void differentSeedsDiverge() {
        Simulation first = new Simulation(1, SimConfig.chaotic(5));
        Simulation second = new Simulation(2, SimConfig.chaotic(5));

        first.run(400);
        second.run(400);

        assertThat(second.trace().lines()).isNotEqualTo(first.trace().lines());
    }

    @Test
    @DisplayName("a run can be replayed in pieces and still land in the same state")
    void steppingIsEquivalentToRunning() {
        Simulation whole = new Simulation(99, SimConfig.chaotic(5));
        Simulation pieces = new Simulation(99, SimConfig.chaotic(5));

        whole.run(300);
        for (int i = 0; i < 300; i++) {
            pieces.step();
        }

        assertThat(pieces.digest()).isEqualTo(whole.digest());
    }
}
