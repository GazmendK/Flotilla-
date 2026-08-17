/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.sim.invariant;

import dev.flotilla.core.NodeId;
import dev.flotilla.core.RaftRole;
import java.util.SortedMap;
import java.util.TreeMap;

public final class ElectionSafety implements Invariant {

    private final SortedMap<Long, NodeId> leaderByTerm = new TreeMap<>();

    @Override
    public String name() {
        return "Election Safety";
    }

    @Override
    public void observe(WorldView world) {
        for (NodeId node : world.nodes()) {
            if (!world.isRunning(node) || world.role(node) != RaftRole.LEADER) {
                continue;
            }
            long term = world.term(node);
            NodeId known = leaderByTerm.putIfAbsent(term, node);
            if (known != null && !known.equals(node)) {
                throw new InvariantViolation(name(), "term " + term + " was led by both " + known + " and " + node);
            }
        }
    }
}
