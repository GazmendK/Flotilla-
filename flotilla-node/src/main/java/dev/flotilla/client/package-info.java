/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * The Java client library.
 *
 * <p>Handles leader discovery, redirects, retries with jittered backoff and the client sessions
 * that make retried requests idempotent. It also records an operation history that the
 * linearizability checker consumes, which is what allows correctness to be verified against a
 * real cluster and not only against the simulation.
 */
@NullMarked
package dev.flotilla.client;

import org.jspecify.annotations.NullMarked;
