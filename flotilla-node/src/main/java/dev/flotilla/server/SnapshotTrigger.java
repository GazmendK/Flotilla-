/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.server;

@FunctionalInterface
public interface SnapshotTrigger {

    void afterApply(long appliedIndex, long appliedTerm, long bytesApplied);

    static SnapshotTrigger none() {
        return (index, term, bytes) -> {};
    }
}
