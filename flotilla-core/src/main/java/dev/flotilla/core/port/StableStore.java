/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.core.port;

import dev.flotilla.core.HardState;
import java.util.Optional;

public interface StableStore {
    Optional<HardState> load();

    void persist(HardState state);
}
