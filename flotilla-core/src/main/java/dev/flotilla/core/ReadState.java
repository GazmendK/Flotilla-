/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.core;

import java.util.Objects;

@RaftSpec(value = "§6.4 Processing read-only queries more efficiently", source = RaftSpec.Source.DISSERTATION)
public record ReadState(Bytes requestId, long readIndex) {
    public ReadState {
        Objects.requireNonNull(requestId, "requestId");
        if (readIndex < 0) {
            throw new IllegalArgumentException("readIndex must not be negative, was " + readIndex);
        }
    }
}
