/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.core;

import java.util.Objects;

public sealed interface TransferResult {

    record Started(NodeId target) implements TransferResult {
        public Started {
            Objects.requireNonNull(target, "target");
        }
    }

    record Rejected(String reason) implements TransferResult {
        public Rejected {
            Objects.requireNonNull(reason, "reason");
        }
    }

    default boolean isStarted() {
        return this instanceof Started;
    }
}
