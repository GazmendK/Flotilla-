/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.server;

import dev.flotilla.core.Bytes;
import java.util.Objects;

public record Applied(long index, Bytes response) {

    public Applied {
        Objects.requireNonNull(response, "response");
    }
}
