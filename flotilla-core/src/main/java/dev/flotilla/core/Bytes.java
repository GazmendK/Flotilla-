/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.core;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

public final class Bytes implements Comparable<Bytes> {
    public static final Bytes EMPTY = new Bytes(new byte[0]);

    private final byte[] value;

    private Bytes(byte[] value) {
        this.value = value;
    }

    public static Bytes copyOf(byte[] source) {
        Objects.requireNonNull(source, "source");
        return source.length == 0 ? EMPTY : new Bytes(source.clone());
    }

    public static Bytes wrap(byte[] owned) {
        Objects.requireNonNull(owned, "owned");
        return owned.length == 0 ? EMPTY : new Bytes(owned);
    }

    public static Bytes ofUtf8(String text) {
        Objects.requireNonNull(text, "text");
        return wrap(text.getBytes(StandardCharsets.UTF_8));
    }

    public int size() {
        return value.length;
    }

    public boolean isEmpty() {
        return value.length == 0;
    }

    public byte[] toByteArray() {
        return value.clone();
    }

    public String toUtf8() {
        return new String(value, StandardCharsets.UTF_8);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Bytes bytes && Arrays.equals(value, bytes.value);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(value);
    }

    @Override
    public int compareTo(Bytes other) {
        return Arrays.compareUnsigned(value, other.value);
    }

    @Override
    public String toString() {
        if (value.length == 0) {
            return "Bytes[]";
        }
        int shown = Math.min(value.length, 8);
        String hex = HexFormat.of().formatHex(value, 0, shown);
        String suffix = shown < value.length ? "..." : "";
        return "Bytes[" + value.length + "B 0x" + hex + suffix + "]";
    }
}
