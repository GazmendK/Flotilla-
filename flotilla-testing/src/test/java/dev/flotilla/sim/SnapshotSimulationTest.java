/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.sim;

import static org.assertj.core.api.Assertions.assertThat;

import dev.flotilla.core.NodeId;
import java.util.stream.LongStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class SnapshotSimulationTest {

    private static final int SEEDS = 20;

    static LongStream seeds() {
        String single = System.getProperty("flotilla.sim.seed");
        if (single != null) {
            return LongStream.of(Long.parseLong(single));
        }
        return LongStream.rangeClosed(1, SEEDS);
    }

    @Test
    @DisplayName("chaos really does force snapshots to be taken and transferred, or this file proves nothing")
    void snapshotsAreActuallyExercised() {
        long taken = 0;
        long installed = 0;
        for (long seed = 1; seed <= SEEDS; seed++) {
            Simulation simulation = new Simulation(seed, SimConfig.chaotic(5));
            simulation.run(800);
            taken += simulation.snapshotsTaken();
            installed += simulation.snapshotInstalls();
        }

        assertThat(taken)
                .as("no snapshot was ever taken, so compaction was never tested")
                .isPositive();
        assertThat(installed)
                .as("no snapshot ever reached a follower, so the transfer was never tested")
                .isPositive();
    }

    @ParameterizedTest(name = "seed {0}")
    @MethodSource("seeds")
    @DisplayName("a node that was down while the cluster compacted past it catches up and holds the same state")
    void aNodeDownThroughCompactionCatchesUp(long seed) {
        Simulation simulation = new Simulation(seed, SimConfig.calm(5));
        simulation.runWithoutFaults(120);
        NodeId leader = simulation.leaders().getFirst();
        NodeId lagging = simulation.nodes().stream()
                .filter(id -> !id.equals(leader))
                .findFirst()
                .orElseThrow();

        simulation.crash(lagging);
        simulation.runWithoutFaults(600);

        long base = simulation.node(leader).log().firstIndex() - 1;
        assertThat(base)
                .as("the leader never compacted, so nothing forced a snapshot transfer")
                .isPositive();
        assertThat(simulation.node(lagging).log().lastIndex())
                .as("the node that was down must be behind the compacted prefix for this to mean anything")
                .isLessThan(base);

        simulation.restart(lagging);
        simulation.runWithoutFaults(300);
        simulation.settle(100);

        assertThat(simulation.snapshotInstalls()).isPositive();
        assertThat(simulation.node(lagging).stateMachine().lastApplied())
                .isEqualTo(simulation.node(leader).stateMachine().lastApplied());
        assertThat(simulation.node(lagging).stateMachine().digest())
                .as("catching up from a snapshot has to reproduce the state, not just the log")
                .isEqualTo(simulation.node(leader).stateMachine().digest());
    }

    @ParameterizedTest(name = "seed {0}")
    @MethodSource("seeds")
    @DisplayName("crashes during snapshotting and installing leave every invariant intact")
    void snapshotsSurviveCrashesAndPartitions(long seed) {
        Simulation simulation = new Simulation(seed, SimConfig.adversarial(5));
        simulation.run(600);

        simulation.heal();
        simulation.restartAll();
        simulation.runWithoutFaults(600);

        assertThat(simulation.leaders()).hasSize(1);
        assertThat(simulation.highestCommitIndex()).isPositive();
    }
}
