/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * The node runtime that turns the pure core into a running process.
 *
 * <p>All Raft state transitions happen on a single event-loop thread that consumes a bounded
 * queue, so the core needs no locks and the concurrency argument stays small enough to write
 * down. Persistence, application of committed entries and snapshotting each run on their own
 * thread so that a slow disk or a slow state machine cannot stall replication.
 */
@NullMarked
package dev.flotilla.server;

import org.jspecify.annotations.NullMarked;
