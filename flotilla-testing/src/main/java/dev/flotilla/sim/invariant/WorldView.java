/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.sim.invariant;

import dev.flotilla.core.LogEntry;
import dev.flotilla.core.NodeId;
import dev.flotilla.core.RaftRole;
import java.util.List;
import java.util.SortedSet;

public interface WorldView {

    long time();

    SortedSet<NodeId> nodes();

    boolean isRunning(NodeId id);

    int restarts(NodeId id);

    long term(NodeId id);

    RaftRole role(NodeId id);

    long commitIndex(NodeId id);

    List<LogEntry> log(NodeId id);

    List<LogEntry> appliedThisStep(NodeId id);
}
