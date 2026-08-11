/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.core.message;

import dev.flotilla.core.NodeId;
import dev.flotilla.core.RaftSpec;
import java.util.Objects;

@RaftSpec("Figure 13, InstallSnapshot RPC")
public record InstallSnapshotResponse(NodeId from, NodeId to, long term, long bytesReceived, boolean installed)
        implements RaftMessage {
    public InstallSnapshotResponse {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (bytesReceived < 0) {
            throw new IllegalArgumentException("bytesReceived must not be negative, was " + bytesReceived);
        }
    }
}
