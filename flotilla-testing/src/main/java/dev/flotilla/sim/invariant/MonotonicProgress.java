/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.sim.invariant;

import dev.flotilla.core.NodeId;
import java.util.SortedMap;
import java.util.TreeMap;

public final class MonotonicProgress implements Invariant {

    private final SortedMap<NodeId, Long> highestTerm = new TreeMap<>();
    private final SortedMap<NodeId, Long> highestCommit = new TreeMap<>();
    private final SortedMap<NodeId, Integer> lifetime = new TreeMap<>();

    @Override
    public String name() {
        return "Monotonic Progress";
    }

    @Override
    public void observe(WorldView world) {
        for (NodeId node : world.nodes()) {
            resetOnRestart(node, world.restarts(node));

            long term = world.term(node);
            Long knownTerm = highestTerm.get(node);
            if (knownTerm != null && term < knownTerm) {
                throw new InvariantViolation(name(), node + " moved from term " + knownTerm + " back to term " + term);
            }
            highestTerm.put(node, term);

            long commit = world.commitIndex(node);
            Long knownCommit = highestCommit.get(node);
            if (knownCommit != null && commit < knownCommit) {
                throw new InvariantViolation(
                        name(), node + " moved its commit index from " + knownCommit + " back to " + commit);
            }
            highestCommit.put(node, commit);

            if (commit > world.log(node).size()) {
                throw new InvariantViolation(
                        name(),
                        node + " has commit index " + commit + " but only "
                                + world.log(node).size() + " entries in its log");
            }
        }
    }

    private void resetOnRestart(NodeId node, int restarts) {
        Integer known = lifetime.get(node);
        if (known == null || known != restarts) {
            lifetime.put(node, restarts);
            highestTerm.remove(node);
            highestCommit.remove(node);
        }
    }
}
