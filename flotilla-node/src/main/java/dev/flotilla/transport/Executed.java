/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.transport;

import dev.flotilla.core.Bytes;
import java.util.Objects;

public record Executed(long index, Bytes result) {

    public Executed {
        Objects.requireNonNull(result, "result");
    }
}
