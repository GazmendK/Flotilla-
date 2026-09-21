/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.sim.invariant;

import dev.flotilla.core.ClusterConfig;
import dev.flotilla.core.ClusterConfigCodec;
import dev.flotilla.core.EntryType;
import dev.flotilla.core.LogEntry;
import dev.flotilla.core.NodeId;
import dev.flotilla.core.RaftRole;
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

public final class OneChangeAtATime implements Invariant {

    @Override
    public String name() {
        return "One Change At A Time";
    }

    @Override
    public void observe(WorldView world) {
        for (NodeId node : world.nodes()) {
            checkEachStepTouchesOneServer(node, world.log(node));
            if (world.isRunning(node) && world.role(node) == RaftRole.LEADER) {
                checkAtMostOneInFlight(node, world.log(node), world.commitIndex(node));
            }
        }
    }

    private void checkEachStepTouchesOneServer(NodeId node, LogView log) {
        @Nullable ClusterConfig previous = null;
        long previousIndex = 0;
        for (LogEntry entry : log.entries()) {
            if (entry.type() != EntryType.CONFIGURATION) {
                continue;
            }
            ClusterConfig current = ClusterConfigCodec.decode(entry.data());
            if (previous != null) {
                Set<NodeId> touched = touched(previous, current);
                if (touched.size() > 1) {
                    throw new InvariantViolation(
                            name(),
                            node + " holds configuration " + current + " at index " + entry.index()
                                    + " right after " + previous + " at index " + previousIndex
                                    + "; the step between them changes " + touched);
                }
            }
            previous = current;
            previousIndex = entry.index();
        }
    }

    private static Set<NodeId> touched(ClusterConfig before, ClusterConfig after) {
        Set<NodeId> everyone = new TreeSet<>(before.voters());
        everyone.addAll(before.learners());
        everyone.addAll(after.voters());
        everyone.addAll(after.learners());
        Set<NodeId> touched = new TreeSet<>();
        for (NodeId id : everyone) {
            if (before.isVoter(id) != after.isVoter(id) || before.isLearner(id) != after.isLearner(id)) {
                touched.add(id);
            }
        }
        return touched;
    }

    private void checkAtMostOneInFlight(NodeId leader, LogView log, long commitIndex) {
        int uncommitted = 0;
        for (LogEntry entry : log.entries()) {
            if (entry.index() > commitIndex && entry.type() == EntryType.CONFIGURATION) {
                uncommitted++;
            }
        }
        if (uncommitted > 1) {
            throw new InvariantViolation(
                    name(),
                    leader + " leads with " + uncommitted + " uncommitted configurations above commit index "
                            + commitIndex + "; two single-server changes in flight are a two-server change");
        }
    }
}
