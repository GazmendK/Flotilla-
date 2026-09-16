/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.storage;

import dev.flotilla.core.Snapshot;
import dev.flotilla.core.port.SnapshotStore;

public interface WritableSnapshotStore extends SnapshotStore {

    void save(Snapshot snapshot);
}
