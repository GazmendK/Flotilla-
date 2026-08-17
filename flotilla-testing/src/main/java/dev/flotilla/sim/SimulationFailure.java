/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.sim;

import dev.flotilla.sim.invariant.InvariantViolation;

public final class SimulationFailure extends RuntimeException {

    private final long seed;

    public SimulationFailure(long seed, InvariantViolation violation, String trace) {
        super(buildMessage(seed, violation, trace), violation);
        this.seed = seed;
    }

    private static String buildMessage(long seed, InvariantViolation violation, String trace) {
        return violation.getMessage()
                + System.lineSeparator()
                + System.lineSeparator()
                + "Seed: " + seed
                + System.lineSeparator()
                + "Reproduce by re-running the failing test with a single seed:"
                + System.lineSeparator()
                + "  ./gradlew :flotilla-testing:test --tests '*<failing test class>*' -Dflotilla.sim.seed=" + seed
                + System.lineSeparator()
                + System.lineSeparator()
                + "Recent events:"
                + System.lineSeparator()
                + trace;
    }

    public long seed() {
        return seed;
    }
}
