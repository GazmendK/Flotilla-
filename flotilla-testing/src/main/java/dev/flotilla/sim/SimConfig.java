/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.sim;

public record SimConfig(
        int voters,
        long minLatencyUnits,
        long maxLatencyUnits,
        double slowLinkProbability,
        long slowLinkLatencyUnits,
        double dropProbability,
        double duplicateProbability,
        double crashProbability,
        double restartProbability,
        double repartitionProbability,
        double healProbability,
        double proposalProbability,
        int logMatchingCheckEveryTicks,
        int maxEntriesPerAppend) {

    public SimConfig {
        if (voters < 1) {
            throw new IllegalArgumentException("voters must be at least 1, was " + voters);
        }
        if (minLatencyUnits < 1 || maxLatencyUnits < minLatencyUnits) {
            throw new IllegalArgumentException("latency range must be positive and ordered, was " + minLatencyUnits
                    + ".." + maxLatencyUnits + "; a latency of zero would let messages loop within one instant");
        }
        if (logMatchingCheckEveryTicks < 1) {
            throw new IllegalArgumentException(
                    "logMatchingCheckEveryTicks must be at least 1, was " + logMatchingCheckEveryTicks);
        }
    }

    public static SimConfig calm(int voters) {
        return new SimConfig(voters, 20, 200, 0.0, 0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.2, 10, 64);
    }

    public static SimConfig chaotic(int voters) {
        return new SimConfig(voters, 20, 400, 0.02, 4000, 0.25, 0.05, 0.01, 0.10, 0.02, 0.05, 0.3, 10, 64);
    }

    public static SimConfig adversarial(int voters) {
        return new SimConfig(voters, 20, 400, 0.02, 4000, 0.30, 0.05, 0.04, 0.25, 0.08, 0.05, 0.5, 5, 1);
    }
}
