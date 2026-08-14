/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.core.state;

import dev.flotilla.core.NodeId;
import dev.flotilla.core.RaftRole;
import dev.flotilla.core.RaftSpec;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;

@RaftSpec("§5.2 Leader election")
public final class Candidate implements RaftState {

    private final boolean preVote;
    private final SortedMap<NodeId, Boolean> votes = new TreeMap<>();

    public Candidate(boolean preVote) {
        this.preVote = preVote;
    }

    public boolean isPreVote() {
        return preVote;
    }

    public void recordVote(NodeId from, boolean granted) {
        votes.putIfAbsent(Objects.requireNonNull(from, "from"), granted);
    }

    public int grantedCount() {
        return count(true);
    }

    public int rejectedCount() {
        return count(false);
    }

    private int count(boolean granted) {
        int total = 0;
        for (Map.Entry<NodeId, Boolean> vote : votes.entrySet()) {
            if (vote.getValue() == granted) {
                total++;
            }
        }
        return total;
    }

    @Override
    public RaftRole role() {
        return preVote ? RaftRole.PRE_CANDIDATE : RaftRole.CANDIDATE;
    }
}
