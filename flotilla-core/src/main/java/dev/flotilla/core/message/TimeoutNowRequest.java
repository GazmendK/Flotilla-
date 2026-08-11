/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.core.message;

import dev.flotilla.core.NodeId;
import dev.flotilla.core.RaftSpec;
import java.util.Objects;

@RaftSpec(value = "§3.10 Leadership transfer extension", source = RaftSpec.Source.DISSERTATION)
public record TimeoutNowRequest(NodeId from, NodeId to, long term) implements RaftMessage {
    public TimeoutNowRequest {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
    }
}
