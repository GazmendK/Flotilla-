/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.core.state;

import dev.flotilla.core.NodeId;
import dev.flotilla.core.RaftRole;
import org.jspecify.annotations.Nullable;

public record Follower(@Nullable NodeId leaderId) implements RaftState {

    public static Follower withoutLeader() {
        return new Follower(null);
    }

    @Override
    public RaftRole role() {
        return RaftRole.FOLLOWER;
    }
}
