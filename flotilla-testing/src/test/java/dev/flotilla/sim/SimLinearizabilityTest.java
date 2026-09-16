/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.sim;

import static org.assertj.core.api.Assertions.assertThat;

import dev.flotilla.linearizability.CheckResult;
import dev.flotilla.linearizability.CheckResult.Outcome;
import dev.flotilla.linearizability.CounterexampleRenderer;
import dev.flotilla.linearizability.KvModel;
import dev.flotilla.linearizability.LinearizabilityChecker;
import dev.flotilla.linearizability.Operation;
import dev.flotilla.sim.WorkloadConfig.ReadMode;
import java.time.Duration;
import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class SimLinearizabilityTest {

    private static final int DEFAULT_SEEDS = 40;
    private static final Duration BUDGET = Duration.ofSeconds(60);
    private static final KvModel MODEL = new KvModel();

    static LongStream seeds() {
        String single = System.getProperty("flotilla.sim.seed");
        if (single != null) {
            return LongStream.of(Long.parseLong(single));
        }
        return LongStream.rangeClosed(1, Integer.getInteger("flotilla.sim.seeds", DEFAULT_SEEDS));
    }

    static CheckResult<KvModel.State, KvModel.Input, KvModel.Output> runAndCheck(long seed, ReadMode mode) {
        Simulation simulation = new Simulation(seed, SimConfig.chaotic(5), WorkloadConfig.clients(6, mode));
        simulation.run(600);
        simulation.heal();
        simulation.restartAll();
        simulation.settle(100);

        List<Operation<KvModel.Input, KvModel.Output>> history =
                simulation.workload().history();
        long reads = history.stream()
                .filter(operation -> operation.input() instanceof KvModel.Get && !operation.isIndeterminate())
                .count();
        long writes = history.stream()
                .filter(operation -> !(operation.input() instanceof KvModel.Get) && !operation.isIndeterminate())
                .count();
        assertThat(reads)
                .as("seed %d: only %d reads completed, too few for a pass to mean anything", seed, reads)
                .isGreaterThanOrEqualTo(20);
        assertThat(writes)
                .as("seed %d: only %d writes completed, too few for a pass to mean anything", seed, writes)
                .isGreaterThanOrEqualTo(20);
        return new LinearizabilityChecker().check(MODEL, history, BUDGET);
    }

    private static String describe(long seed, CheckResult<KvModel.State, KvModel.Input, KvModel.Output> result) {
        return "seed " + seed + ": " + result
                + result.counterexample()
                        .map(counterexample -> "\n" + CounterexampleRenderer.render(MODEL, counterexample))
                        .orElse("");
    }

    @ParameterizedTest(name = "seed {0}")
    @MethodSource("seeds")
    @DisplayName("reads through ReadIndex stay linearizable under crashes, partitions, drops and snapshots")
    void readIndexIsLinearizable(long seed) {
        ReadMode mode = WorkloadConfig.readModeFromSystemProperty(ReadMode.LINEARIZABLE);

        CheckResult<KvModel.State, KvModel.Input, KvModel.Output> result = runAndCheck(seed, mode);

        assertThat(result.outcome()).as(describe(seed, result)).isEqualTo(Outcome.LINEARIZABLE);
    }

    @ParameterizedTest(name = "seed {0}")
    @MethodSource("seeds")
    @DisplayName("lease reads stay linearizable too, in a simulation whose clocks never drift")
    void leaseReadsAreLinearizableWithoutDrift(long seed) {
        CheckResult<KvModel.State, KvModel.Input, KvModel.Output> result = runAndCheck(seed, ReadMode.LEASE);

        assertThat(result.outcome()).as(describe(seed, result)).isEqualTo(Outcome.LINEARIZABLE);
    }

    @org.junit.jupiter.api.Test
    @DisplayName("reading straight from a replica is caught, which is the proof that the checker is looking")
    void unsafeLocalReadsAreCaught() {
        int caught = 0;
        long firstCaught = -1;
        for (long seed = 1; seed <= 10; seed++) {
            CheckResult<KvModel.State, KvModel.Input, KvModel.Output> result = runAndCheck(seed, ReadMode.UNSAFE_LOCAL);
            if (result.outcome() == Outcome.NOT_LINEARIZABLE) {
                caught++;
                if (firstCaught < 0) {
                    firstCaught = seed;
                }
            }
        }

        assertThat(caught)
                .as("reads served by whichever replica is asked, without confirming anything, must be caught")
                .isGreaterThanOrEqualTo(8);
    }
}
