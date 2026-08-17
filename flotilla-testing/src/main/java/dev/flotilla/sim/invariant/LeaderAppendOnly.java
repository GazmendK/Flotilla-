/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.sim.invariant;

import dev.flotilla.core.LogEntry;
import dev.flotilla.core.NodeId;
import dev.flotilla.core.RaftRole;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;

public final class LeaderAppendOnly implements Invariant {

    private final SortedMap<NodeId, Long> observedTerm = new TreeMap<>();
    private final SortedMap<NodeId, List<LogEntry>> observedLog = new TreeMap<>();

    @Override
    public String name() {
        return "Leader Append-Only";
    }

    @Override
    public void observe(WorldView world) {
        for (NodeId node : world.nodes()) {
            if (!world.isRunning(node) || world.role(node) != RaftRole.LEADER) {
                observedTerm.remove(node);
                observedLog.remove(node);
                continue;
            }

            long term = world.term(node);
            List<LogEntry> current = world.log(node);
            Long previousTerm = observedTerm.get(node);
            List<LogEntry> previous = observedLog.get(node);

            if (previousTerm != null && previous != null && previousTerm == term) {
                if (current.size() < previous.size()) {
                    throw new InvariantViolation(
                            name(),
                            node + " was leader of term " + term + " and its log shrank from " + previous.size()
                                    + " to " + current.size() + " entries");
                }
                for (int i = 0; i < previous.size(); i++) {
                    if (!current.get(i).equals(previous.get(i))) {
                        throw new InvariantViolation(
                                name(),
                                node + " was leader of term " + term + " and rewrote the entry at index "
                                        + previous.get(i).index());
                    }
                }
            }

            observedTerm.put(node, term);
            observedLog.put(node, current);
        }
    }
}
