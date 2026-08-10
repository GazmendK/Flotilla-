/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * The replicated key-value state machine and its client session tracking.
 *
 * <p>Determinism is a correctness requirement here, not a preference: every replica applies the
 * same log and must reach byte-identical state, so this package uses ordered collections, never
 * reads a wall clock and never uses randomness.
 */
@NullMarked
package dev.flotilla.kv;

import org.jspecify.annotations.NullMarked;
