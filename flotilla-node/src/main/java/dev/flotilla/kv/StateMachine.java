/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.kv;

import dev.flotilla.core.Bytes;

public interface StateMachine {

    Bytes apply(long index, Bytes command);

    default void validate(Bytes command) {}

    default Bytes query(Bytes query) {
        throw new UnsupportedOperationException(getClass().getSimpleName() + " answers no queries outside the log");
    }

    default void validateQuery(Bytes query) {
        throw new IllegalArgumentException(getClass().getSimpleName() + " answers no queries outside the log");
    }

    Bytes snapshot();

    default StateCapture capture() {
        Bytes taken = snapshot();
        return () -> taken;
    }

    void restore(Bytes snapshot);

    long lastAppliedIndex();

    static StateMachine discarding() {
        return new StateMachine() {
            private long lastApplied;

            @Override
            public Bytes apply(long index, Bytes command) {
                lastApplied = index;
                return Bytes.EMPTY;
            }

            @Override
            public Bytes snapshot() {
                return Bytes.EMPTY;
            }

            @Override
            public void restore(Bytes snapshot) {}

            @Override
            public long lastAppliedIndex() {
                return lastApplied;
            }
        };
    }
}
