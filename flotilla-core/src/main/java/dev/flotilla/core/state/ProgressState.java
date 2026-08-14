/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.core.state;

import dev.flotilla.core.RaftSpec;

@RaftSpec("§5.3 Log replication")
public enum ProgressState {
    PROBE,
    REPLICATE,
    SNAPSHOT
}
