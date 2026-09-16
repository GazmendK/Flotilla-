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
    private final SortedMap<NodeId, Long> restoredByNode = new TreeMap<>();
    private final SortedMap<Long, Long> digestByIndex = new TreeMap<>();
    private final SortedMap<Long, NodeId> digestWitnessByIndex = new TreeMap<>();

    @Override
    public String name() {
        return "State Machine Safety";
    }

    @Override
    public void observe(WorldView world) {
        for (NodeId node : world.nodes()) {
            boolean cameBack = restarted(node, world.restarts(node));
            boolean installed = installedASnapshot(node, world.restoredFromSnapshotAt(node));
            boolean moved = cameBack || installed;
            for (LogEntry entry : world.appliedThisStep(node)) {
                checkNobodyAppliedSomethingElse(node, entry);
                if (!moved) {
                    checkAppliedInOrder(node, entry, world);
                }
            }
            if (moved) {
                lastAppliedByNode.put(node, world.appliedIndex(node));
            }
            checkReplicasHoldTheSameState(node, world.appliedIndex(node), world.appliedDigest(node));
        }
    }

    private boolean restarted(NodeId node, int restarts) {
        Integer known = lifetimeByNode.put(node, restarts);
        return known != null && known.intValue() != restarts;
    }

    private boolean installedASnapshot(NodeId node, long restoredIndex) {
        Long known = restoredByNode.put(node, restoredIndex);
        return known != null && known.longValue() != restoredIndex;
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

    private void checkAppliedInOrder(NodeId node, LogEntry entry, WorldView world) {
        Long previous = lastAppliedByNode.get(node);
        if (previous != null && entry.index() != previous + 1) {
            throw new InvariantViolation(
                    name(),
                    node + " applied index " + entry.index() + " directly after " + previous
                            + "; entries must be applied exactly once and in order (restarts="
                            + world.restarts(node) + ", restored from snapshot at "
                            + world.restoredFromSnapshotAt(node) + ", applied through "
                            + world.appliedIndex(node) + ", this step "
                            + world.appliedThisStep(node).stream()
                                    .map(LogEntry::index)
                                    .toList() + ")");
        }
        lastAppliedByNode.put(node, entry.index());
    }

    private void checkReplicasHoldTheSameState(NodeId node, long appliedIndex, long digest) {
        if (appliedIndex <= 0) {
            return;
        }
        Long known = digestByIndex.putIfAbsent(appliedIndex, digest);
        if (known == null) {
            digestWitnessByIndex.put(appliedIndex, node);
            return;
        }
        if (known.longValue() != digest) {
            throw new InvariantViolation(
                    name(),
                    node + " has applied through index " + appliedIndex + " and holds state " + digest + ", but "
                            + digestWitnessByIndex.get(appliedIndex) + " reached the same index holding " + known
                            + "; two replicas at the same index must hold the same state");
        }
    }
}
