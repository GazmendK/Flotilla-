/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.core;

import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

public record SoftState(@Nullable NodeId leaderId, RaftRole role) {
    public SoftState {
        Objects.requireNonNull(role, "role");
    }

    public static SoftState follower() {
        return new SoftState(null, RaftRole.FOLLOWER);
    }

    public Optional<NodeId> leader() {
        return Optional.ofNullable(leaderId);
    }

    public boolean hasLeader() {
        return leaderId != null;
    }
}
