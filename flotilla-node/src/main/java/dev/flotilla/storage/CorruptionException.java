/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.storage;

public final class CorruptionException extends RuntimeException {

    public CorruptionException(String detail) {
        super(detail);
    }

    public static CorruptionException at(Object file, long offset, String detail) {
        return new CorruptionException(file + " at offset " + offset + ": " + detail);
    }
}
