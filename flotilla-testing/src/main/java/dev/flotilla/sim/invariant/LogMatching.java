/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.sim.invariant;

import dev.flotilla.core.LogEntry;
import dev.flotilla.core.NodeId;
import java.util.ArrayList;
import java.util.List;

public final class LogMatching implements Invariant {

    @Override
    public String name() {
        return "Log Matching";
    }

    @Override
    public boolean isExpensive() {
        return true;
    }

    @Override
    public void observe(WorldView world) {
        List<NodeId> nodes = new ArrayList<>(world.nodes());
        for (int a = 0; a < nodes.size(); a++) {
            for (int b = a + 1; b < nodes.size(); b++) {
                compare(nodes.get(a), world.log(nodes.get(a)), nodes.get(b), world.log(nodes.get(b)));
            }
        }
    }

    private void compare(NodeId first, List<LogEntry> firstLog, NodeId second, List<LogEntry> secondLog) {
        int common = Math.min(firstLog.size(), secondLog.size());
        boolean diverged = false;
        for (int i = 0; i < common; i++) {
            LogEntry left = firstLog.get(i);
            LogEntry right = secondLog.get(i);
            if (!diverged) {
                if (left.term() == right.term()) {
                    if (!left.equals(right)) {
                        throw new InvariantViolation(
                                name(),
                                first + " and " + second + " hold different entries at index " + left.index()
                                        + " despite both being in term " + left.term());
                    }
                } else {
                    diverged = true;
                }
            } else if (left.term() == right.term()) {
                throw new InvariantViolation(
                        name(),
                        first + " and " + second + " diverge before index " + left.index() + " yet share term "
                                + left.term() + " there, which no correct pair of logs can");
            }
        }
    }
}
