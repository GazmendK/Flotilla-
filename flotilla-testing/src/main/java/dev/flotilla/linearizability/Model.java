/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.linearizability;

import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

public interface Model<S, I, O> {

    S initialState();

    Optional<S> step(S state, I input, @Nullable O output);

    default boolean isReadOnly(I input) {
        return false;
    }

    default List<List<Operation<I, O>>> partition(List<Operation<I, O>> operations) {
        return List.of(operations);
    }

    default String describePartition(List<Operation<I, O>> partition) {
        return "the whole history";
    }

    default String describeOperation(I input, @Nullable O output) {
        return input + " -> " + (output == null ? "?" : output);
    }

    default String describeState(S state) {
        return String.valueOf(state);
    }
}
