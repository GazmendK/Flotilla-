/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.server;

import dev.flotilla.core.Bytes;
import dev.flotilla.core.ClusterConfig;
import dev.flotilla.core.ConfChange;
import dev.flotilla.core.NodeId;
import dev.flotilla.core.message.RaftMessage;
import dev.flotilla.transport.ClusterStatus;
import dev.flotilla.transport.ReadConsistency;
import java.util.concurrent.CompletableFuture;

public sealed interface NodeEvent {

    record Tick() implements NodeEvent {}

    record Inbound(RaftMessage message) implements NodeEvent {}

    record Proposal(Bytes command, CompletableFuture<Applied> result) implements NodeEvent {}

    record Shutdown() implements NodeEvent {}

    record Compact(long throughIndex) implements NodeEvent {}

    record Read(Bytes query, ReadConsistency consistency, CompletableFuture<Applied> result) implements NodeEvent {}

    record Membership(ConfChange change, CompletableFuture<ClusterConfig> result) implements NodeEvent {}

    record Transfer(NodeId target, CompletableFuture<NodeId> result) implements NodeEvent {}

    record Describe(boolean leaderOnly, CompletableFuture<ClusterStatus> result) implements NodeEvent {}
}
