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
public record ReadIndexResponse(NodeId from, NodeId to, long term, Bytes requestId, long readIndex)
        implements RaftMessage {
    public ReadIndexResponse {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(requestId, "requestId");
        if (readIndex < 0) {
            throw new IllegalArgumentException("readIndex must not be negative, was " + readIndex);
        }
    }
}
