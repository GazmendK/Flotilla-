/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.server;

import dev.flotilla.core.NodeId;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

public final class NotLeaderException extends RuntimeException {

    @Nullable
    private final NodeId leader;

    public NotLeaderException(@Nullable NodeId leader) {
        super(leader == null ? "This node is not the leader and does not know who is" : "The leader is " + leader);
        this.leader = leader;
    }

    public Optional<NodeId> leader() {
        return Optional.ofNullable(leader);
    }
}
