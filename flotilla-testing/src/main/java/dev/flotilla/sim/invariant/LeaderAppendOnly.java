/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.sim.invariant;

import dev.flotilla.core.LogEntry;
import dev.flotilla.core.NodeId;
import dev.flotilla.core.RaftRole;
import java.util.SortedMap;
import java.util.TreeMap;

public final class LeaderAppendOnly implements Invariant {

    private final SortedMap<NodeId, Long> observedTerm = new TreeMap<>();
    private final SortedMap<NodeId, LogView> observedLog = new TreeMap<>();

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
            LogView current = world.log(node);
            Long previousTerm = observedTerm.get(node);
            LogView previous = observedLog.get(node);

            if (previousTerm != null && previous != null && previousTerm == term) {
                compare(node, term, previous, current);
            }

            observedTerm.put(node, term);
            observedLog.put(node, current);
        }
    }

    private void compare(NodeId node, long term, LogView previous, LogView current) {
        if (current.lastIndex() < previous.lastIndex()) {
            throw new InvariantViolation(
                    name(),
                    node + " was leader of term " + term + " and its log shrank from index " + previous.lastIndex()
                            + " back to " + current.lastIndex());
        }
        long from = Math.max(previous.firstIndex(), current.firstIndex());
        for (long index = from; index <= previous.lastIndex(); index++) {
            LogEntry was = previous.at(index).orElseThrow();
            LogEntry now = current.at(index).orElseThrow();
            if (!was.equals(now)) {
                throw new InvariantViolation(
                        name(), node + " was leader of term " + term + " and rewrote the entry at index " + index);
            }
        }
    }
}
