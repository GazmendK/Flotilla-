/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.core.message;

import dev.flotilla.core.Bytes;
import dev.flotilla.core.NodeId;
import dev.flotilla.core.RaftSpec;
import java.util.Objects;

@RaftSpec(value = "§6.4 Processing read-only queries more efficiently", source = RaftSpec.Source.DISSERTATION)
public record ReadIndexRequest(NodeId from, NodeId to, long term, Bytes requestId) implements RaftMessage {
    public ReadIndexRequest {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(requestId, "requestId");
    }
}
