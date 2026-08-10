/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * Durable storage: the segmented write-ahead log, the stable store and snapshot files.
 *
 * <p>This package owns the promise that survives a power cut. It is responsible for the on-disk
 * format, for fsync ordering, for detecting partially written records, and for recovering a valid
 * log prefix after a crash rather than refusing to start.
 */
@NullMarked
package dev.flotilla.storage;

import org.jspecify.annotations.NullMarked;
