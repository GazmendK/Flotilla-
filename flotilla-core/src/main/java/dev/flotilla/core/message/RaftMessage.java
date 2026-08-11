/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.core.message;

import dev.flotilla.core.NodeId;
import dev.flotilla.core.RaftSpec;

@RaftSpec("Figure 2, Rules for Servers")
public sealed interface RaftMessage
        permits AppendEntriesRequest,
                AppendEntriesResponse,
                InstallSnapshotRequest,
                InstallSnapshotResponse,
                ReadIndexRequest,
                ReadIndexResponse,
                RequestVoteRequest,
                RequestVoteResponse,
                TimeoutNowRequest {
    NodeId from();

    NodeId to();

    long term();
}
