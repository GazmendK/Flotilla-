/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.storage;

import dev.flotilla.core.port.LogStore;

public interface DurableLogStore extends LogStore, AutoCloseable {

    void sync();

    @Override
    void close();
}
