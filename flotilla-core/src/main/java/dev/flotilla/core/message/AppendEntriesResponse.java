/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.core.message;

import dev.flotilla.core.NodeId;
import dev.flotilla.core.RaftSpec;
import java.util.Objects;

@RaftSpec("§5.3 Log replication")
public record AppendEntriesResponse(
        NodeId from, NodeId to, long term, boolean success, long matchIndex, long conflictIndex, long conflictTerm)
        implements RaftMessage {
    public AppendEntriesResponse {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
    }

    public static AppendEntriesResponse accepted(NodeId from, NodeId to, long term, long matchIndex) {
        return new AppendEntriesResponse(from, to, term, true, matchIndex, 0, 0);
    }

    public static AppendEntriesResponse rejected(
            NodeId from, NodeId to, long term, long conflictIndex, long conflictTerm) {
        return new AppendEntriesResponse(from, to, term, false, 0, conflictIndex, conflictTerm);
    }
}
