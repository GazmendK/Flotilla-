/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.kv;

import dev.flotilla.core.Bytes;

public interface StateMachine {

    void apply(long index, Bytes command);

    static StateMachine discarding() {
        return (index, command) -> {};
    }
}
