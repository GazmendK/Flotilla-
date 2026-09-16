/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.linearizability;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

public record Operation<I, O>(
        int id, long process, I input, @Nullable O output, long call, long returned) {

    public static final long NEVER = Long.MAX_VALUE;

    public Operation {
        Objects.requireNonNull(input, "input");
        if (returned < call) {
            throw new IllegalArgumentException(
                    "operation " + id + " returned at " + returned + " before it was called at " + call);
        }
        if (returned == NEVER && output != null) {
            throw new IllegalArgumentException("operation " + id
                    + " never returned, so it cannot have an observed output; an outcome nobody saw is unknown");
        }
        if (returned != NEVER && output == null) {
            throw new IllegalArgumentException("operation " + id + " returned at " + returned
                    + " without an output; a completed operation must say what it observed");
        }
    }

    public static <I, O> Operation<I, O> completed(int id, long process, I input, O output, long call, long returned) {
        return new Operation<>(id, process, input, Objects.requireNonNull(output, "output"), call, returned);
    }

    public static <I, O> Operation<I, O> indeterminate(int id, long process, I input, long call) {
        return new Operation<>(id, process, input, null, call, NEVER);
    }

    public boolean isIndeterminate() {
        return returned == NEVER;
    }
}
