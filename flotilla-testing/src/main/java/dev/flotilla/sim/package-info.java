/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * Deterministic simulation testing.
 *
 * <p>Runs entire clusters in one thread on virtual time, over a virtual network that can delay,
 * drop, duplicate, reorder and partition messages, and that can crash nodes in a way that loses
 * every unsynced byte. All five safety properties from Figure 3 of the Raft paper are checked
 * after each step.
 *
 * <p>Every run is driven by a seed, so a failure found after millions of steps is reproducible
 * with a single command. This module implements the same ports as the production runtime, so what
 * the simulation exercises is the shipped consensus code, not a model of it.
 */
@NullMarked
package dev.flotilla.sim;

import org.jspecify.annotations.NullMarked;
