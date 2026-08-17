/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.storage;

import dev.flotilla.core.InMemoryLogStore;
import dev.flotilla.core.port.LogStore;

class InMemoryLogStoreContractTest extends LogStoreContractTest {

    @Override
    protected LogStore createStore() {
        return new InMemoryLogStore();
    }
}
