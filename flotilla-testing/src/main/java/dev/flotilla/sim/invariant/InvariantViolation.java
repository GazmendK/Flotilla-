/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.sim.invariant;

public final class InvariantViolation extends RuntimeException {

    private final String invariant;

    public InvariantViolation(String invariant, String detail) {
        super(invariant + " violated: " + detail);
        this.invariant = invariant;
    }

    public String invariant() {
        return invariant;
    }
}
