/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.linearizability;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

public record CheckResult<S, I, O>(
        Outcome outcome,
        int partitions,
        int undecidedPartitions,
        int operations,
        Duration elapsed,
        @Nullable Counterexample<S, I, O> counterexampleOrNull) {

    public enum Outcome {
        LINEARIZABLE,
        NOT_LINEARIZABLE,
        UNKNOWN
    }

    public CheckResult {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(elapsed, "elapsed");
        if ((outcome == Outcome.NOT_LINEARIZABLE) != (counterexampleOrNull != null)) {
            throw new IllegalArgumentException(
                    "a counterexample exists exactly when the history is not linearizable, but the outcome was "
                            + outcome);
        }
    }

    public boolean isLinearizable() {
        return outcome == Outcome.LINEARIZABLE;
    }

    public Optional<Counterexample<S, I, O>> counterexample() {
        return Optional.ofNullable(counterexampleOrNull);
    }

    @Override
    public String toString() {
        return outcome + " (" + operations + " operations in " + partitions + " partitions, " + undecidedPartitions
                + " undecided, " + elapsed.toMillis() + " ms)";
    }
}
