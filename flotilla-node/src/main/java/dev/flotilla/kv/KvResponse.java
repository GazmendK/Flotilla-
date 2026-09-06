/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.kv;

import dev.flotilla.core.Bytes;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

public sealed interface KvResponse {

    record Value(@Nullable Bytes value) implements KvResponse {
        public Optional<Bytes> asOptional() {
            return Optional.ofNullable(value);
        }

        public boolean isPresent() {
            return value != null;
        }
    }

    record Swapped(boolean swapped) implements KvResponse {}

    record Entries(List<KeyValue> entries) implements KvResponse {
        public Entries {
            entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
        }
    }

    Value ABSENT = new Value(null);

    static Value of(@Nullable Bytes value) {
        return value == null ? ABSENT : new Value(value);
    }
}
