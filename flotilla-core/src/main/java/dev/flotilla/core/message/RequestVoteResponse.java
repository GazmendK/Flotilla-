/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.core.message;

import dev.flotilla.core.NodeId;
import dev.flotilla.core.RaftSpec;
import java.util.Objects;

@RaftSpec(value = "§9.6 Preventing disruptions when a server rejoins", source = RaftSpec.Source.DISSERTATION)
public record RequestVoteResponse(NodeId from, NodeId to, long term, boolean voteGranted, boolean preVote)
        implements RaftMessage {
    public RequestVoteResponse {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
    }
}
