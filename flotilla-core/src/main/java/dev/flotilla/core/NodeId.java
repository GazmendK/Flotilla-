/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.core;

import java.util.Objects;
import java.util.regex.Pattern;

public record NodeId(String value) implements Comparable<NodeId> {
    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

    public NodeId {
        Objects.requireNonNull(value, "value");
        if (!VALID.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid node id '" + value
                    + "'. Must start with a letter or digit and contain only letters, digits, "
                    + "'.', '_' or '-', at most 64 characters.");
        }
    }

    public static NodeId of(String value) {
        return new NodeId(value);
    }

    @Override
    public int compareTo(NodeId other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
