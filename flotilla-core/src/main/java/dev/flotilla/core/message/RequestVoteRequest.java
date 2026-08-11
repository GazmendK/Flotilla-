/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.core.message;

import dev.flotilla.core.NodeId;
import dev.flotilla.core.RaftSpec;
import java.util.Objects;

@RaftSpec("§5.4.1 Election restriction")
public record RequestVoteRequest(
        NodeId from, NodeId to, long term, long lastLogIndex, long lastLogTerm, boolean preVote)
        implements RaftMessage {
    public RequestVoteRequest {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
    }
}
