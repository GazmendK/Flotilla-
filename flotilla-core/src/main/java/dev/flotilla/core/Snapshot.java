/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.core;

import java.util.Objects;

@RaftSpec("§7 Log compaction")
public record Snapshot(long lastIncludedIndex, long lastIncludedTerm, ClusterConfig cluster, Bytes data) {
    public Snapshot {
        Objects.requireNonNull(cluster, "cluster");
        Objects.requireNonNull(data, "data");
        if (lastIncludedIndex < 0) {
            throw new IllegalArgumentException("lastIncludedIndex must not be negative, was " + lastIncludedIndex);
        }
        if (lastIncludedTerm < 0) {
            throw new IllegalArgumentException("lastIncludedTerm must not be negative, was " + lastIncludedTerm);
        }
    }

    public boolean covers(long index) {
        return index <= lastIncludedIndex;
    }

    public int sizeBytes() {
        return data.size();
    }

    @Override
    public String toString() {
        return "Snapshot[through " + lastIncludedIndex + " term=" + lastIncludedTerm + " bytes=" + data.size() + "]";
    }
}
