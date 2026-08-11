/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LogEntryTest {
    @Test
    @DisplayName("the log is 1-based; index 0 means 'no entry' and is never a real entry")
    void rejectsIndexZero() {
        assertThatThrownBy(() -> LogEntry.normal(1, 0, Bytes.EMPTY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1-based");
    }

    @Test
    void rejectsNegativeTerm() {
        assertThatThrownBy(() -> LogEntry.normal(-1, 1, Bytes.EMPTY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("term");
    }

    @Test
    void noOpCarriesNoPayload() {
        LogEntry entry = LogEntry.noOp(3, 7);

        assertThat(entry.type()).isEqualTo(EntryType.NOOP);
        assertThat(entry.data()).isEqualTo(Bytes.EMPTY);
        assertThat(entry.term()).isEqualTo(3);
        assertThat(entry.index()).isEqualTo(7);
    }

    @Test
    @DisplayName("entries with equal term and index and payload are equal, since Bytes compares by content")
    void equalityIsStructural() {
        LogEntry first = LogEntry.normal(1, 1, Bytes.ofUtf8("x"));
        LogEntry second = LogEntry.normal(1, 1, Bytes.ofUtf8("x"));

        assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
    }

    @Test
    void sizeGrowsWithThePayload() {
        int small = LogEntry.normal(1, 1, Bytes.ofUtf8("x")).approximateSizeBytes();
        int large = LogEntry.normal(1, 1, Bytes.copyOf(new byte[100])).approximateSizeBytes();

        assertThat(large - small).isEqualTo(99);
    }
}
