/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.core;

import dev.flotilla.core.port.SnapshotStore;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

public final class InMemorySnapshotStore implements SnapshotStore {

    @Nullable
    private Snapshot latest;

    @Override
    public Optional<Snapshot> latest() {
        return Optional.ofNullable(latest);
    }

    public void save(Snapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (latest != null && snapshot.lastIncludedIndex() < latest.lastIncludedIndex()) {
            throw new IllegalArgumentException("Cannot replace the snapshot through index " + latest.lastIncludedIndex()
                    + " with an older one through index " + snapshot.lastIncludedIndex() + ".");
        }
        latest = snapshot;
    }

    @Override
    public String toString() {
        return "InMemorySnapshotStore[" + (latest == null ? "empty" : latest.toString()) + "]";
    }
}
