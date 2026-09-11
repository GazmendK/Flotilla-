/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.kv;

import dev.flotilla.core.Bytes;
import java.util.Objects;

public record Session(long lastSequence, Bytes lastResponse, long lastActiveIndex) {

    public Session {
        Objects.requireNonNull(lastResponse, "lastResponse");
        if (lastSequence < 0) {
            throw new IllegalArgumentException("lastSequence must not be negative, was " + lastSequence);
        }
    }

    static Session opened(long index) {
        return new Session(0, Bytes.EMPTY, index);
    }

    Session answered(long sequence, Bytes response, long index) {
        return new Session(sequence, response, index);
    }

    Session touched(long index) {
        return new Session(lastSequence, lastResponse, index);
    }
}
