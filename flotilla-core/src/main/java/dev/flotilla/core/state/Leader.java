/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.core.state;

import dev.flotilla.core.NodeId;
import dev.flotilla.core.RaftRole;
import dev.flotilla.core.RaftSpec;
import java.util.Collections;
import java.util.Objects;
import java.util.SortedSet;
import java.util.TreeSet;

@RaftSpec(value = "§6.2 Routing requests to the leader", source = RaftSpec.Source.DISSERTATION)
public final class Leader implements RaftState {

    private final SortedSet<NodeId> recentlyActive = new TreeSet<>();

    public void markActive(NodeId peer) {
        recentlyActive.add(Objects.requireNonNull(peer, "peer"));
    }

    public SortedSet<NodeId> recentlyActive() {
        return Collections.unmodifiableSortedSet(recentlyActive);
    }

    public int recentlyActiveCount() {
        return recentlyActive.size();
    }

    public void resetActivity(NodeId self) {
        recentlyActive.clear();
        recentlyActive.add(Objects.requireNonNull(self, "self"));
    }

    @Override
    public RaftRole role() {
        return RaftRole.LEADER;
    }
}
