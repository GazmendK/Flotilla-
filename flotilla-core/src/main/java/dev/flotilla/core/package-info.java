/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * The Raft consensus algorithm as a pure state machine.
 *
 * <p>Everything in this package is deterministic by construction. The core performs no I/O, starts
 * no threads, never reads a wall clock and never uses unseeded randomness. Time enters as logical
 * ticks, randomness enters through an injected source, and side effects leave as data: the core
 * describes what should happen, and the runtime layer carries it out.
 *
 * <p>The reason is testability. A deterministic core lets an entire cluster -- with partitions,
 * message loss and crashes -- run in a single thread, so any failure is reproducible from a seed.
 * Everything else this design buys (no locks in the core, reusability as a library) is a side
 * effect of that goal.
 *
 * <p>This contract is enforced mechanically by the architecture test rather than by convention.
 *
 * @see <a href="https://raft.github.io/raft.pdf">In Search of an Understandable Consensus
 *     Algorithm (Extended Version)</a>
 */
@NullMarked
package dev.flotilla.core;

import org.jspecify.annotations.NullMarked;
