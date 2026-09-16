/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.sim;

import static org.assertj.core.api.Assertions.assertThat;

import dev.flotilla.core.Bytes;
import dev.flotilla.core.NodeId;
import dev.flotilla.linearizability.CheckResult;
import dev.flotilla.linearizability.CheckResult.Outcome;
import dev.flotilla.linearizability.CounterexampleRenderer;
import dev.flotilla.linearizability.History;
import dev.flotilla.linearizability.KvModel;
import dev.flotilla.linearizability.LinearizabilityChecker;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class DeposedLeaderReadTest {

    private static final KvModel MODEL = new KvModel();
    private static final String KEY = "deposed";
    private static final int SEEDS = 10;
    private static final int OFFSETS = 10;

    private static Optional<NodeId> otherLeader(Simulation simulation, NodeId old) {
        return simulation.leaders().stream().filter(id -> !id.equals(old)).findFirst();
    }

    private static boolean overlapped(Simulation simulation, NodeId old) {
        for (int tick = 0; tick < 40; tick++) {
            simulation.step();
            if (!simulation.leaders().contains(old)) {
                return false;
            }
            if (otherLeader(simulation, old).isPresent()) {
                return true;
            }
        }
        return false;
    }

    private static CheckResult<KvModel.State, KvModel.Input, KvModel.Output> scenario(
            Simulation simulation, NodeId old, WorkloadConfig.ReadMode mode) {
        NodeId current = otherLeader(simulation, old).orElseThrow();
        History<KvModel.Input, KvModel.Output> history = new History<>();

        SimNode newLeader = simulation.node(current);
        long index = newLeader.raft().lastLogIndex() + 1;
        newLeader.stateMachine().watch(index);
        int write = history.invoke(1, new KvModel.Put(KEY, "fresh"), simulation.time());
        simulation.propose(current, SimStateMachine.put("write", KEY, "fresh").toUtf8());
        for (int tick = 0; tick < 20; tick++) {
            simulation.step();
            Optional<SimStateMachine.Applied> applied = newLeader.stateMachine().takeApplied(index);
            if (applied.isPresent()) {
                assertThat(applied.get().operation()).isEqualTo("write");
                history.ok(
                        write,
                        new KvModel.Value(SimStateMachine.fromNil(applied.get().response())),
                        simulation.time());
                break;
            }
        }

        Bytes requestId = Bytes.ofUtf8("stale-read");
        int read = history.invoke(2, new KvModel.Get(KEY), simulation.time() + 1);
        SimNode oldLeader = simulation.node(old);
        boolean accepted = mode == WorkloadConfig.ReadMode.LEASE
                ? simulation.leaseRead(old, requestId)
                : simulation.readIndex(old, requestId);
        if (accepted) {
            Optional<Long> readIndex = Optional.empty();
            for (int tick = 0; tick < 30; tick++) {
                if (readIndex.isEmpty()) {
                    readIndex = oldLeader.takeReadIndex(requestId);
                }
                if (readIndex.isPresent() && oldLeader.stateMachine().lastApplied() >= readIndex.get()) {
                    history.ok(
                            read,
                            new KvModel.Value(oldLeader.stateMachine().get(KEY).orElse(null)),
                            simulation.time() + 2);
                    break;
                }
                simulation.step();
            }
        } else {
            history.fail(read, simulation.time() + 2);
        }
        return new LinearizabilityChecker().check(MODEL, history.operations(), Duration.ofSeconds(10));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(
            value = WorkloadConfig.ReadMode.class,
            names = {"LINEARIZABLE", "LEASE"})
    @DisplayName(
            "a leader cut off in a minority, still believing it leads, never serves a read the majority has overtaken")
    void aDeposedLeaderServesNoStaleRead(WorkloadConfig.ReadMode mode) {
        int overlaps = 0;
        for (long seed = 1; seed <= SEEDS; seed++) {
            for (int offset = 0; offset < OFFSETS; offset++) {
                Simulation simulation = new Simulation(seed, SimConfig.calm(5), new WorkloadConfig(0, 0, 1, mode, 1));
                simulation.runWithoutFaults(60);
                List<NodeId> leaders = simulation.leaders();
                if (leaders.size() != 1) {
                    continue;
                }
                NodeId old = leaders.getFirst();
                simulation.runWithoutFaults(offset);
                NodeId companion = simulation.nodes().stream()
                        .filter(id -> !id.equals(old))
                        .findFirst()
                        .orElseThrow();
                simulation.partition(List.of(old, companion));
                if (!overlapped(simulation, old)) {
                    continue;
                }
                overlaps++;

                CheckResult<KvModel.State, KvModel.Input, KvModel.Output> result = scenario(simulation, old, mode);

                assertThat(result.outcome())
                        .as(
                                "seed %d, offset %d: %s%n%s",
                                seed,
                                offset,
                                result,
                                result.counterexample()
                                        .map(counterexample -> CounterexampleRenderer.render(MODEL, counterexample))
                                        .orElse(""))
                        .isEqualTo(Outcome.LINEARIZABLE);
            }
        }

        assertThat(overlaps)
                .as("no run produced two leaders at once, so the dangerous window was never tested")
                .isPositive();
    }
}
