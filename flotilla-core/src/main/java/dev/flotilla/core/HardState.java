/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.core;

import java.util.Optional;
import org.jspecify.annotations.Nullable;

@RaftSpec("Figure 2, Persistent state on all servers")
public record HardState(long currentTerm, @Nullable NodeId votedFor, long commitIndex) {
    public static final HardState INITIAL = new HardState(0, null, 0);

    public HardState {
        if (currentTerm < 0) {
            throw new IllegalArgumentException("currentTerm must not be negative, was " + currentTerm);
        }
        if (commitIndex < 0) {
            throw new IllegalArgumentException("commitIndex must not be negative, was " + commitIndex);
        }
    }

    public Optional<NodeId> vote() {
        return Optional.ofNullable(votedFor);
    }

    public boolean hasVoted() {
        return votedFor != null;
    }
}
