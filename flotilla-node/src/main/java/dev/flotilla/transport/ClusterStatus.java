/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.transport;

import dev.flotilla.core.CatchUpStatus;
import dev.flotilla.core.ClusterConfig;
import dev.flotilla.core.NodeId;
import java.util.Collections;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

public record ClusterStatus(
        NodeId node,
        @Nullable NodeId leader,
        long term,
        ClusterConfig configuration,
        long configurationIndex,
        boolean configurationCommitted,
        long commitIndex,
        long appliedIndex,
        SortedMap<NodeId, CatchUpStatus> catchUp) {

    public ClusterStatus {
        Objects.requireNonNull(node, "node");
        Objects.requireNonNull(configuration, "configuration");
        catchUp = Collections.unmodifiableSortedMap(new TreeMap<>(Objects.requireNonNull(catchUp, "catchUp")));
    }

    public Optional<NodeId> knownLeader() {
        return Optional.ofNullable(leader);
    }
}
