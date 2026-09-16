/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.core.message;

import dev.flotilla.core.NodeId;
import dev.flotilla.core.RaftSpec;
import dev.flotilla.core.Snapshot;
import java.util.Objects;

@RaftSpec("Figure 13, InstallSnapshot RPC")
public record InstallSnapshotRequest(NodeId from, NodeId to, long term, Snapshot snapshot) implements RaftMessage {
    public InstallSnapshotRequest {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(snapshot, "snapshot");
    }

    public long lastIncludedIndex() {
        return snapshot.lastIncludedIndex();
    }

    public long lastIncludedTerm() {
        return snapshot.lastIncludedTerm();
    }
}
