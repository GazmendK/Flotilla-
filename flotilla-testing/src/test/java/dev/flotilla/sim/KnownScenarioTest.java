/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.sim;

import static org.assertj.core.api.Assertions.assertThat;

import dev.flotilla.core.NodeId;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class KnownScenarioTest {

    private static final List<NodeId> ALL =
            List.of(NodeId.of("n1"), NodeId.of("n2"), NodeId.of("n3"), NodeId.of("n4"), NodeId.of("n5"));

    private static Simulation settledCluster(long seed) {
        Simulation simulation = new Simulation(seed, SimConfig.calm(5));
        simulation.run(200);
        assertThat(simulation.leaders()).hasSize(1);
        return simulation;
    }

    private static List<NodeId> twoNodesOtherThan(NodeId leader) {
        return ALL.stream().filter(node -> !node.equals(leader)).limit(2).toList();
    }

    @Test
    @DisplayName("the majority side of a partition keeps committing")
    void theMajoritySideKeepsCommitting() {
        Simulation simulation = settledCluster(11);
        NodeId leader = simulation.leaders().getFirst();

        simulation.partition(twoNodesOtherThan(leader));
        simulation.run(100);
        long before = simulation.highestCommitIndex();
        simulation.run(300);

        assertThat(simulation.leaders()).containsExactly(leader);
        assertThat(simulation.highestCommitIndex()).isGreaterThan(before);
    }

    @Test
    @DisplayName("the minority side commits nothing, which is the price of consistency")
    void theMinoritySideStalls() {
        Simulation simulation = settledCluster(12);
        NodeId leader = simulation.leaders().getFirst();
        List<NodeId> minority = twoNodesOtherThan(leader);

        simulation.partition(minority);
        simulation.run(100);
        long strandedCommit = simulation.node(minority.getFirst()).raft().commitIndex();
        simulation.run(400);

        assertThat(simulation.node(minority.getFirst()).raft().commitIndex()).isEqualTo(strandedCommit);
    }

    @Test
    @DisplayName("a partition heals and the stranded nodes catch up")
    void healingLetsTheStrandedNodesCatchUp() {
        Simulation simulation = settledCluster(13);
        NodeId leader = simulation.leaders().getFirst();
        List<NodeId> minority = twoNodesOtherThan(leader);

        simulation.partition(minority);
        simulation.run(300);
        simulation.heal();
        simulation.run(300);

        long leaderCommit = simulation.node(leader).raft().commitIndex();
        assertThat(simulation.node(minority.getFirst()).raft().commitIndex()).isEqualTo(leaderCommit);
    }

    @Test
    @DisplayName("a crashed leader is replaced")
    void aCrashedLeaderIsReplaced() {
        Simulation simulation = settledCluster(14);
        NodeId leader = simulation.leaders().getFirst();

        simulation.crash(leader);
        simulation.run(300);

        assertThat(simulation.leaders()).hasSize(1).doesNotContain(leader);
    }

    @Test
    @DisplayName("the whole cluster can crash and come back")
    void theWholeClusterRecovers() {
        Simulation simulation = settledCluster(15);
        long committedBefore = simulation.highestCommitIndex();

        ALL.forEach(simulation::crash);
        simulation.run(50);
        assertThat(simulation.leaders()).isEmpty();

        ALL.forEach(simulation::restart);
        simulation.run(400);

        assertThat(simulation.leaders()).hasSize(1);
        assertThat(simulation.highestCommitIndex()).isGreaterThanOrEqualTo(committedBefore);
    }

    @Test
    @DisplayName("a leader that crashes immediately after winning still leaves a usable cluster")
    void aLeaderCrashingRightAfterTheElection() {
        Simulation simulation = new Simulation(16, SimConfig.calm(5));
        simulation.run(200);
        NodeId first = simulation.leaders().getFirst();
        simulation.crash(first);
        simulation.run(200);
        NodeId second = simulation.leaders().getFirst();

        simulation.crash(second);
        simulation.run(400);

        assertThat(simulation.leaders()).hasSize(1);
    }

    @Test
    @DisplayName("links that are occasionally very slow do not break anything")
    void slowLinksAreSurvivable() {
        SimConfig slow = new SimConfig(5, 20, 200, 0.30, 6000, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.2, 10, 64);
        Simulation simulation = new Simulation(17, slow);

        simulation.run(600);

        assertThat(simulation.highestCommitIndex()).isPositive();
    }
}
