/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.core.port;

import dev.flotilla.core.Snapshot;
import java.util.Optional;

@FunctionalInterface
public interface SnapshotStore {
    Optional<Snapshot> latest();

    static SnapshotStore none() {
        return Optional::empty;
    }
}
