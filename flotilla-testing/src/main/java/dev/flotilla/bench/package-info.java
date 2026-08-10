/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * Benchmarks: JMH microbenchmarks for the hot paths and an open-loop macro load generator.
 *
 * <p>The load generator sends requests according to an arrival schedule rather than waiting for
 * the previous response, so reported tail latencies are not distorted by coordinated omission.
 */
@NullMarked
package dev.flotilla.bench;

import org.jspecify.annotations.NullMarked;
