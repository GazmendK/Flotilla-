/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.core;

import java.util.Objects;

public sealed interface ConfChangeResult {

    record Accepted(long index, ClusterConfig configuration) implements ConfChangeResult {
        public Accepted {
            Objects.requireNonNull(configuration, "configuration");
        }
    }

    record Rejected(String reason) implements ConfChangeResult {
        public Rejected {
            Objects.requireNonNull(reason, "reason");
        }
    }

    default boolean isAccepted() {
        return this instanceof Accepted;
    }
}
