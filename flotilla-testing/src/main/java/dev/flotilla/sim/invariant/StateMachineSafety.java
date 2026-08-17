/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.sim.invariant;

import dev.flotilla.core.LogEntry;
import dev.flotilla.core.NodeId;
import java.util.SortedMap;
import java.util.TreeMap;

public final class StateMachineSafety implements Invariant {

    private final SortedMap<Long, LogEntry> appliedByIndex = new TreeMap<>();
    private final SortedMap<NodeId, Long> lastAppliedByNode = new TreeMap<>();
    private final SortedMap<NodeId, Integer> lifetimeByNode = new TreeMap<>();

    @Override
    public String name() {
        return "State Machine Safety";
    }

    @Override
    public void observe(WorldView world) {
        for (NodeId node : world.nodes()) {
            resetOnRestart(node, world.restarts(node));
            for (LogEntry entry : world.appliedThisStep(node)) {
                checkNobodyAppliedSomethingElse(node, entry);
                checkAppliedInOrder(node, entry);
            }
        }
    }

    private void resetOnRestart(NodeId node, int restarts) {
        Integer known = lifetimeByNode.get(node);
        if (known == null || known != restarts) {
            lifetimeByNode.put(node, restarts);
            lastAppliedByNode.remove(node);
        }
    }

    private void checkNobodyAppliedSomethingElse(NodeId node, LogEntry entry) {
        LogEntry known = appliedByIndex.putIfAbsent(entry.index(), entry);
        if (known != null && !known.equals(entry)) {
            throw new InvariantViolation(
                    name(),
                    "index " + entry.index() + " was applied as " + known + " elsewhere and as " + entry + " on "
                            + node);
        }
    }

    private void checkAppliedInOrder(NodeId node, LogEntry entry) {
        Long previous = lastAppliedByNode.get(node);
        if (previous != null && entry.index() != previous + 1) {
            throw new InvariantViolation(
                    name(),
                    node + " applied index " + entry.index() + " directly after " + previous
                            + "; entries must be applied exactly once and in order");
        }
        lastAppliedByNode.put(node, entry.index());
    }
}
