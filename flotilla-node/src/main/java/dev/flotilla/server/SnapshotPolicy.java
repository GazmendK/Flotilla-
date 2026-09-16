/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.server;

public record SnapshotPolicy(long entriesBetweenSnapshots, long bytesBetweenSnapshots) {

    public SnapshotPolicy {
        if (entriesBetweenSnapshots < 1) {
            throw new IllegalArgumentException(
                    "entriesBetweenSnapshots must be at least 1, was " + entriesBetweenSnapshots);
        }
        if (bytesBetweenSnapshots < 1) {
            throw new IllegalArgumentException(
                    "bytesBetweenSnapshots must be at least 1, was " + bytesBetweenSnapshots);
        }
    }

    public static SnapshotPolicy defaults() {
        return new SnapshotPolicy(10_000, 64L * 1024 * 1024);
    }

    public SnapshotPolicy withEntriesBetweenSnapshots(long entries) {
        return new SnapshotPolicy(entries, bytesBetweenSnapshots);
    }

    public SnapshotPolicy withBytesBetweenSnapshots(long bytes) {
        return new SnapshotPolicy(entriesBetweenSnapshots, bytes);
    }

    public boolean isDue(long appliedIndex, long lastSnapshotIndex, long bytesSinceLastSnapshot) {
        return appliedIndex - lastSnapshotIndex >= entriesBetweenSnapshots
                || bytesSinceLastSnapshot >= bytesBetweenSnapshots;
    }
}
