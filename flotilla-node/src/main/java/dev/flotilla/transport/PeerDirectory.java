/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.transport;

import dev.flotilla.core.NodeId;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@FunctionalInterface
public interface PeerDirectory {

    Optional<InetSocketAddress> addressOf(NodeId node);

    static PeerDirectory of(Map<NodeId, InetSocketAddress> addresses) {
        Map<NodeId, InetSocketAddress> fixed = Map.copyOf(Objects.requireNonNull(addresses, "addresses"));
        return node -> Optional.ofNullable(fixed.get(node));
    }
}
