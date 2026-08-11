/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.core.message;

import dev.flotilla.core.LogEntry;
import dev.flotilla.core.NodeId;
import dev.flotilla.core.RaftSpec;
import java.util.List;
import java.util.Objects;

@RaftSpec("Figure 2, AppendEntries RPC")
public record AppendEntriesRequest(
        NodeId from,
        NodeId to,
        long term,
        long prevLogIndex,
        long prevLogTerm,
        List<LogEntry> entries,
        long leaderCommit)
        implements RaftMessage {
    public AppendEntriesRequest {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");

        entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
        if (prevLogIndex < 0) {
            throw new IllegalArgumentException("prevLogIndex must not be negative, was " + prevLogIndex);
        }
    }

    public boolean isHeartbeat() {
        return entries.isEmpty();
    }

    public long lastIndex() {
        return entries.isEmpty() ? prevLogIndex : entries.getLast().index();
    }
}
