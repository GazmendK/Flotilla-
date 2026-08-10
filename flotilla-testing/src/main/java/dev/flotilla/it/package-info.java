/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * Support code for integration tests that run real, separately started nodes.
 *
 * <p>The simulation covers correctness under adversarial conditions; these tests cover the things
 * a simulation cannot: process startup, real sockets, real files and real signals.
 */
@NullMarked
package dev.flotilla.it;

import org.jspecify.annotations.NullMarked;
