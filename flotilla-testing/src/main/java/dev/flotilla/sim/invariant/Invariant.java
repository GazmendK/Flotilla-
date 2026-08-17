/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.sim.invariant;

public interface Invariant {

    String name();

    void observe(WorldView world);

    default boolean isExpensive() {
        return false;
    }
}
