/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.transport;

import dev.flotilla.core.ClusterConfig;
import dev.flotilla.core.ConfChange;
import dev.flotilla.core.NodeId;
import java.util.concurrent.CompletableFuture;

public interface AdminGateway {

    CompletableFuture<ClusterConfig> changeMembership(ConfChange change);

    CompletableFuture<NodeId> transferLeadership(NodeId target);

    CompletableFuture<ClusterStatus> describeCluster(boolean leaderOnly);
}
