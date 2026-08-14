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
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

@RaftSpec("Figure 2, Volatile state on leaders")
public final class Leader implements RaftState {

    private final SortedSet<NodeId> recentlyActive = new TreeSet<>();
    private final SortedMap<NodeId, Progress> peers = new TreeMap<>();

    public void trackPeer(NodeId peer, long nextIndex) {
        peers.put(Objects.requireNonNull(peer, "peer"), new Progress(nextIndex));
    }

    public SortedMap<NodeId, Progress> peers() {
        return Collections.unmodifiableSortedMap(peers);
    }

    @Nullable
    public Progress progressFor(NodeId peer) {
        return peers.get(peer);
    }

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
