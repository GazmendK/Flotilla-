/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.core.message;

import dev.flotilla.core.Bytes;
import dev.flotilla.core.NodeId;
import dev.flotilla.core.RaftSpec;
import java.util.Objects;

@RaftSpec("Figure 13, InstallSnapshot RPC")
public record InstallSnapshotRequest(
        NodeId from,
        NodeId to,
        long term,
        long lastIncludedIndex,
        long lastIncludedTerm,
        long offset,
        Bytes data,
        boolean done)
        implements RaftMessage {
    public InstallSnapshotRequest {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(data, "data");
        if (offset < 0) {
            throw new IllegalArgumentException("offset must not be negative, was " + offset);
        }
        if (lastIncludedIndex < 0) {
            throw new IllegalArgumentException("lastIncludedIndex must not be negative, was " + lastIncludedIndex);
        }
    }
}
