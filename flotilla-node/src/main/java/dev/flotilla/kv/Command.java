/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.kv;

import dev.flotilla.core.Bytes;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

public sealed interface Command {

    record Put(Bytes key, Bytes value) implements Command {
        public Put {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(value, "value");
        }
    }

    record Delete(Bytes key) implements Command {
        public Delete {
            Objects.requireNonNull(key, "key");
        }
    }

    record CompareAndSwap(
            Bytes key, @Nullable Bytes expected, @Nullable Bytes value) implements Command {
        public CompareAndSwap {
            Objects.requireNonNull(key, "key");
        }

        public Optional<Bytes> expectedValue() {
            return Optional.ofNullable(expected);
        }

        public Optional<Bytes> newValue() {
            return Optional.ofNullable(value);
        }
    }

    record Get(Bytes key) implements Command {
        public Get {
            Objects.requireNonNull(key, "key");
        }
    }

    record Scan(Bytes fromInclusive, Bytes toExclusive, int limit) implements Command {
        public Scan {
            Objects.requireNonNull(fromInclusive, "fromInclusive");
            Objects.requireNonNull(toExclusive, "toExclusive");
            if (limit < 0) {
                throw new IllegalArgumentException("limit must not be negative, was " + limit);
            }
        }
    }

    static Command put(String key, String value) {
        return new Put(Bytes.ofUtf8(key), Bytes.ofUtf8(value));
    }

    static Command delete(String key) {
        return new Delete(Bytes.ofUtf8(key));
    }

    static Command get(String key) {
        return new Get(Bytes.ofUtf8(key));
    }
}
