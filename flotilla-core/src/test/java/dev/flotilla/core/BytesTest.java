/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.core;

import static org.assertj.core.api.Assertions.assertThat;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BytesTest {
    @Test
    @DisplayName("equality is by content, which is the whole reason this type exists")
    void equalContentsAreEqual() {
        Bytes first = Bytes.copyOf(new byte[] {1, 2, 3});
        Bytes second = Bytes.copyOf(new byte[] {1, 2, 3});

        assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
    }

    @Test
    @DisplayName("copyOf is defensive: mutating the source afterwards does not change the value")
    void copyOfIsDefensive() {
        byte[] source = {1, 2, 3};
        Bytes copied = Bytes.copyOf(source);

        source[0] = 99;

        assertThat(copied.toByteArray()).containsExactly(1, 2, 3);
    }

    @Test
    @DisplayName("the array handed out is a copy, so a caller cannot mutate a shared log entry")
    void accessorReturnsACopy() {
        Bytes value = Bytes.copyOf(new byte[] {1, 2, 3});

        byte[] extracted = value.toByteArray();
        extracted[0] = 99;

        assertThat(value.toByteArray()).containsExactly(1, 2, 3);
    }

    @Test
    @DisplayName("empty values share one instance")
    void emptyIsCanonical() {
        assertThat(Bytes.copyOf(new byte[0])).isSameAs(Bytes.EMPTY);
        assertThat(Bytes.EMPTY.isEmpty()).isTrue();
        assertThat(Bytes.EMPTY.size()).isZero();
    }

    @Test
    @DisplayName("ordering is unsigned, so byte order matches key order")
    void comparesUnsigned() {
        Bytes low = Bytes.copyOf(new byte[] {0x7F});
        Bytes high = Bytes.copyOf(new byte[] {(byte) 0x80});

        assertThat(low).isLessThan(high);
    }

    @Test
    @DisplayName("a prefix sorts before the longer value that extends it")
    void prefixSortsFirst() {
        assertThat(Bytes.ofUtf8("key")).isLessThan(Bytes.ofUtf8("key1"));
    }

    @Test
    @DisplayName("toString never dumps a whole payload into a log line")
    void toStringIsBounded() {
        Bytes large = Bytes.copyOf(new byte[1024]);

        assertThat(large.toString()).contains("1024B").endsWith("...]").hasSizeLessThan(64);
    }

    @Property(tries = 200)
    @DisplayName("UTF-8 encoding round-trips for arbitrary text")
    void utf8RoundTrips(@ForAll String text) {
        assertThat(Bytes.ofUtf8(text).toUtf8()).isEqualTo(text);
    }

    @Property(tries = 200)
    @DisplayName("equal contents always produce equal hash codes")
    void hashCodeAgreesWithEquals(@ForAll byte[] content) {
        assertThat(Bytes.copyOf(content)).hasSameHashCodeAs(Bytes.copyOf(content));
    }
}
