/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.linearizability;

import java.util.List;
import java.util.Objects;

public record Counterexample<S, I, O>(
        String partition,
        List<Operation<I, O>> operations,
        List<Operation<I, O>> longestLinearization,
        S stateAfterLongest,
        Operation<I, O> culprit) {

    public Counterexample {
        Objects.requireNonNull(partition, "partition");
        operations = List.copyOf(operations);
        longestLinearization = List.copyOf(longestLinearization);
        Objects.requireNonNull(stateAfterLongest, "stateAfterLongest");
        Objects.requireNonNull(culprit, "culprit");
    }
}
