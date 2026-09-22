/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.transport;

import dev.flotilla.core.ClusterConfig;
import dev.flotilla.core.ConfChange;
import dev.flotilla.core.NodeId;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

public interface AdminEndpoint extends AutoCloseable {

    CompletableFuture<ClusterConfig> changeMembership(InetSocketAddress target, ConfChange change, Duration deadline);

    CompletableFuture<NodeId> transferLeadership(InetSocketAddress target, NodeId leader, Duration deadline);

    CompletableFuture<ClusterStatus> describeCluster(InetSocketAddress target, boolean leaderOnly, Duration deadline);

    @Override
    void close();
}
