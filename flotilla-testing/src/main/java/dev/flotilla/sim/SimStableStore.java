/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.sim;

import dev.flotilla.core.HardState;
import dev.flotilla.core.port.StableStore;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

public final class SimStableStore implements StableStore {

    @Nullable
    private HardState durable;

    @Override
    public Optional<HardState> load() {
        return Optional.ofNullable(durable);
    }

    @Override
    public void persist(HardState state) {
        durable = Objects.requireNonNull(state, "state");
    }

    public HardState recovered() {
        return durable == null ? HardState.INITIAL : durable;
    }
}
