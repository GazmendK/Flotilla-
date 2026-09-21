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
        NodeId from,
        NodeId to,
        long term,
        long lastLogIndex,
        long lastLogTerm,
        boolean preVote,
        boolean leadershipTransfer)
        implements RaftMessage {
    public RequestVoteRequest {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (preVote && leadershipTransfer) {
            throw new IllegalArgumentException("A leadership transfer skips the pre-vote; it cannot be one");
        }
    }

    public RequestVoteRequest(NodeId from, NodeId to, long term, long lastLogIndex, long lastLogTerm, boolean preVote) {
        this(from, to, term, lastLogIndex, lastLogTerm, preVote, false);
    }
}
