/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.transport;

public enum ReadConsistency {
    LINEARIZABLE,
    LEASE,
    STALE
}
