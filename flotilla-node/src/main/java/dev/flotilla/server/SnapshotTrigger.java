/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.server;

import dev.flotilla.core.ClusterConfig;

@FunctionalInterface
public interface SnapshotTrigger {

    void afterApply(long appliedIndex, long appliedTerm, ClusterConfig configuration, long bytesApplied);

    static SnapshotTrigger none() {
        return (index, term, configuration, bytes) -> {};
    }
}
