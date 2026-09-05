/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.server;

import dev.flotilla.core.Bytes;
import dev.flotilla.core.message.RaftMessage;
import java.util.concurrent.CompletableFuture;

public sealed interface NodeEvent {

    record Tick() implements NodeEvent {}

    record Inbound(RaftMessage message) implements NodeEvent {}

    record Proposal(Bytes command, CompletableFuture<Long> result) implements NodeEvent {}

    record Shutdown() implements NodeEvent {}
}
