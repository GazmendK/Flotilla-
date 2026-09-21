/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.sim;

import static org.assertj.core.api.Assertions.assertThat;

import dev.flotilla.core.ClusterConfig;
import dev.flotilla.core.ConfChange;
import dev.flotilla.core.ConfChangeResult;
import dev.flotilla.core.NodeId;
import dev.flotilla.core.RaftNode;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.stream.LongStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class MembershipChaosTest {

    private static final int DEFAULT_SEEDS = 60;
    private static final int CHAOS_TICKS = 1_500;
    private static final int SPARES = 2;
    private static final double CHANGE_PROBABILITY = 0.05;

    enum Kind {
        ADD_LEARNER,
        PROMOTE,
        REMOVE,
        TRANSFER
    }

    private static final List<Kind> KINDS = List.of(Kind.values());

    static LongStream seeds() {
        String single = System.getProperty("flotilla.sim.seed");
        if (single != null) {
            return LongStream.of(Long.parseLong(single));
        }
        int count = Integer.getInteger("flotilla.sim.seeds", DEFAULT_SEEDS);
        long offset = Long.getLong("flotilla.sim.offset", 0L);
        return LongStream.range(0, count).map(index -> offset + index + 1);
    }

    private static final class Driver {

        private final Simulation simulation;
        private final SplittableRandom random;
        private final Map<Kind, Integer> accepted = new EnumMap<>(Kind.class);
        private int rejected;

        Driver(Simulation simulation, long seed) {
            this.simulation = simulation;
            this.random = new SplittableRandom(seed ^ 0x5DEECE66DL);
            for (Kind kind : Kind.values()) {
                accepted.put(kind, 0);
            }
        }

        void run(int ticks) {
            for (int tick = 0; tick < ticks; tick++) {
                simulation.step();
                checkLeadersAreEntitled();
                if (random.nextDouble() < CHANGE_PROBABILITY) {
                    changeSomething();
                }
            }
        }

        private void changeSomething() {
            List<NodeId> leaders = simulation.leaders();
            if (leaders.isEmpty()) {
                return;
            }
            NodeId leader = leaders.get(random.nextInt(leaders.size()));
            ClusterConfig configuration = simulation.node(leader).raft().configuration();
            List<NodeId> outsiders = new ArrayList<>();
            for (NodeId id : simulation.nodes()) {
                if (!configuration.isVoter(id) && !configuration.isLearner(id)) {
                    outsiders.add(id);
                }
            }
            List<NodeId> learners = List.copyOf(configuration.learners());
            List<NodeId> members = new ArrayList<>(configuration.voters());
            members.addAll(learners);
            List<NodeId> otherVoters = configuration.voters().stream()
                    .filter(id -> !id.equals(leader))
                    .toList();

            Kind kind = KINDS.get(random.nextInt(KINDS.size()));
            boolean started =
                    switch (kind) {
                        case ADD_LEARNER ->
                            !outsiders.isEmpty() && propose(leader, new ConfChange.AddLearner(pick(outsiders)));
                        case PROMOTE -> !learners.isEmpty() && propose(leader, new ConfChange.Promote(pick(learners)));
                        case REMOVE -> propose(leader, new ConfChange.Remove(pick(members)));
                        case TRANSFER ->
                            !otherVoters.isEmpty()
                                    && simulation
                                            .transferLeadership(leader, pick(otherVoters))
                                            .isStarted();
                    };
            if (started) {
                accepted.merge(kind, 1, Integer::sum);
            } else {
                rejected++;
            }
        }

        private boolean propose(NodeId leader, ConfChange change) {
            ConfChangeResult result = simulation.proposeConfChange(leader, change);
            if (result instanceof ConfChangeResult.Rejected rejection) {
                assertThat(rejection.reason()).as("a refusal has to say why").isNotBlank();
            }
            return result.isAccepted();
        }

        private NodeId pick(List<NodeId> candidates) {
            return candidates.get(random.nextInt(candidates.size()));
        }

        private void checkLeadersAreEntitled() {
            for (NodeId id : simulation.leaders()) {
                RaftNode raft = simulation.node(id).raft();
                if (!raft.configuration().isVoter(id)) {
                    assertThat(raft.configurationIndex())
                            .as(
                                    "seed %d: %s leads without being a voter, which is only allowed while the change "
                                            + "that removed it is still uncommitted",
                                    simulation.seed(), id)
                            .isGreaterThan(raft.commitIndex());
                }
            }
        }
    }

    private static String describe(Simulation simulation) {
        StringBuilder out = new StringBuilder();
        for (NodeId id : simulation.nodes()) {
            RaftNode raft = simulation.node(id).raft();
            out.append(id)
                    .append(' ')
                    .append(raft.role())
                    .append(" term=")
                    .append(raft.currentTerm())
                    .append(" commit=")
                    .append(raft.commitIndex())
                    .append(" last=")
                    .append(raft.lastLogIndex())
                    .append(" config@")
                    .append(raft.configurationIndex())
                    .append('=')
                    .append(raft.configuration())
                    .append(System.lineSeparator());
        }
        return out.toString();
    }

    @ParameterizedTest(name = "seed {0}")
    @MethodSource("seeds")
    @DisplayName("members come and go under crashes and partitions, and every safety property still holds")
    void membershipChangesUnderChaos(long seed) {
        Simulation simulation = new Simulation(seed, SimConfig.chaotic(3), WorkloadConfig.none(), SPARES);
        Driver driver = new Driver(simulation, seed);

        driver.run(CHAOS_TICKS);

        simulation.heal();
        simulation.restartAll();
        simulation.settle(600);

        List<NodeId> leaders = simulation.leaders();
        assertThat(leaders)
                .as(
                        "seed %d: once the faults stop, some configuration must be able to elect a leader%n%s",
                        seed, describe(simulation))
                .hasSize(1);
        RaftNode leader = simulation.node(leaders.getFirst()).raft();
        ClusterConfig finalConfiguration = leader.configuration();
        assertThat(leader.configurationIndex())
                .as("seed %d: the last configuration must commit", seed)
                .isLessThanOrEqualTo(leader.commitIndex());
        for (NodeId member : finalConfiguration.voters()) {
            RaftNode raft = simulation.node(member).raft();
            assertThat(raft.configuration())
                    .as("seed %d: %s must have caught up with the configuration", seed, member)
                    .isEqualTo(finalConfiguration);
            assertThat(raft.commitIndex()).isEqualTo(leader.commitIndex());
        }

        int total =
                driver.accepted.values().stream().mapToInt(Integer::intValue).sum();
        System.out.println("MEMBERSHIP seed " + seed + ": " + driver.accepted + ", rejected " + driver.rejected
                + ", final " + finalConfiguration + ", term " + leader.currentTerm());
        assertThat(total)
                .as("seed %d: too few changes were made for a pass to mean anything", seed)
                .isGreaterThanOrEqualTo(5);
    }
}
