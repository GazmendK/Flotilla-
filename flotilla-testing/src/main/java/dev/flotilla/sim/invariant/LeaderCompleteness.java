/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.sim.invariant;

import dev.flotilla.core.LogEntry;
import dev.flotilla.core.NodeId;
import dev.flotilla.core.RaftRole;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

public final class LeaderCompleteness implements Invariant {

    private final SortedMap<Long, LogEntry> committed = new TreeMap<>();
    private final SortedMap<Long, Long> committedByTerm = new TreeMap<>();

    @Override
    public String name() {
        return "Leader Completeness";
    }

    @Override
    public void observe(WorldView world) {
        long highestTerm = highestTerm(world);
        recordCommittedEntries(world, highestTerm);
        checkLaterLeadersHoldThemAll(world);
    }

    private static long highestTerm(WorldView world) {
        long highest = 0;
        for (NodeId node : world.nodes()) {
            highest = Math.max(highest, world.term(node));
        }
        return highest;
    }

    private void recordCommittedEntries(WorldView world, long highestTerm) {
        for (NodeId node : world.nodes()) {
            long commit = world.commitIndex(node);
            for (LogEntry entry : world.log(node)) {
                if (entry.index() > commit) {
                    break;
                }
                LogEntry known = committed.putIfAbsent(entry.index(), entry);
                if (known == null) {
                    committedByTerm.put(entry.index(), highestTerm);
                } else if (!known.equals(entry)) {
                    throw new InvariantViolation(
                            name(),
                            "index " + entry.index() + " was committed as " + known + " and also as " + entry
                                    + " (seen on " + node + ")");
                }
            }
        }
    }

    private void checkLaterLeadersHoldThemAll(WorldView world) {
        for (NodeId node : world.nodes()) {
            if (!world.isRunning(node) || world.role(node) != RaftRole.LEADER) {
                continue;
            }
            long term = world.term(node);
            List<LogEntry> log = world.log(node);

            for (Map.Entry<Long, LogEntry> entry : committed.entrySet()) {
                Long commitTerm = committedByTerm.get(entry.getKey());
                if (commitTerm == null || commitTerm >= term) {
                    continue;
                }
                long index = entry.getKey();
                if (index > log.size()) {
                    throw new InvariantViolation(
                            name(),
                            node + " leads term " + term + " but is missing committed index " + index
                                    + ", which was committed by term " + commitTerm);
                }
                LogEntry own = log.get((int) (index - 1));
                if (!own.equals(entry.getValue())) {
                    throw new InvariantViolation(
                            name(),
                            node + " leads term " + term + " but holds " + own + " where committed index " + index
                                    + " is " + entry.getValue() + ", committed by term " + commitTerm);
                }
            }
        }
    }
}
