/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.kv;

import dev.flotilla.core.Bytes;
import java.util.Objects;

public record KeyValue(Bytes key, Bytes value) implements Comparable<KeyValue> {

    public KeyValue {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
    }

    @Override
    public int compareTo(KeyValue other) {
        return key.compareTo(other.key);
    }
}
