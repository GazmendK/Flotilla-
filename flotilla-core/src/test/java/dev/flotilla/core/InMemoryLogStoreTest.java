/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class InMemoryLogStoreTest {
    private final InMemoryLogStore log = new InMemoryLogStore();

    private static List<LogEntry> entries(long term, long fromIndex, int count) {
        return LongStream.range(0, count)
                .mapToObj(i -> LogEntry.normal(term, fromIndex + i, Bytes.ofUtf8("v" + (fromIndex + i))))
                .toList();
    }

    @Test
    @DisplayName("an empty log reports first=1, last=0, so boundary arithmetic works without special cases")
    void emptyLogUsesTheOneBasedConvention() {
        assertThat(log.firstIndex()).isEqualTo(1);
        assertThat(log.lastIndex()).isZero();
        assertThat(log.isEmpty()).isTrue();
        assertThat(log.entryAt(1)).isEmpty();
    }

    @Test
    @DisplayName("term at index 0 is 0: the position before the log, used as prevLogTerm")
    void termAtIndexZeroIsZero() {
        assertThat(log.termAt(0)).isZero();
    }

    @Test
    void appendsAndReadsBack() {
        log.append(entries(1, 1, 3));

        assertThat(log.lastIndex()).isEqualTo(3);
        assertThat(log.termAt(2)).isEqualTo(1);
        assertThat(log.entryAt(2)).map(LogEntry::data).contains(Bytes.ofUtf8("v2"));
    }

    @Test
    @DisplayName("appending must continue the log; overwriting requires an explicit truncate first")
    void rejectsAppendThatDoesNotContinueTheLog() {
        log.append(entries(1, 1, 3));

        assertThatThrownBy(() -> log.append(entries(2, 3, 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must start at index 4")
                .hasMessageContaining("Truncate the conflicting suffix first");
    }

    @Test
    void rejectsNonContiguousBatches() {
        List<LogEntry> gappy = List.of(LogEntry.noOp(1, 1), LogEntry.noOp(1, 3));

        assertThatThrownBy(() -> log.append(gappy))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("contiguous");
    }

    @Test
    void truncateRemovesTheSuffixAndNothingElse() {
        log.append(entries(1, 1, 5));

        log.truncateSuffixFrom(3);

        assertThat(log.lastIndex()).isEqualTo(2);
        assertThat(log.entryAt(3)).isEmpty();
        assertThat(log.entryAt(2)).isPresent();
    }

    @Test
    @DisplayName("truncating past the end is a no-op, because a stale rejection may arrive twice")
    void truncatingBeyondTheEndIsHarmless() {
        log.append(entries(1, 1, 2));

        log.truncateSuffixFrom(99);

        assertThat(log.lastIndex()).isEqualTo(2);
    }

    @Test
    void truncateThenAppendReplacesADivergentSuffix() {
        log.append(entries(1, 1, 5));

        log.truncateSuffixFrom(3);
        log.append(entries(2, 3, 2));

        assertThat(log.lastIndex()).isEqualTo(4);
        assertThat(log.termAt(3)).isEqualTo(2);
        assertThat(log.termAt(2)).isEqualTo(1);
    }

    @Test
    void readingHonoursTheEntryCountBudget() {
        log.append(entries(1, 1, 10));

        assertThat(log.entriesFrom(1, 3, Long.MAX_VALUE)).hasSize(3);
    }

    @Test
    void readingHonoursTheByteBudget() {
        log.append(entries(1, 1, 10));
        long twoEntries = log.entryAt(1).orElseThrow().approximateSizeBytes() * 2L;

        assertThat(log.entriesFrom(1, 100, twoEntries)).hasSize(2);
    }

    @Test
    @DisplayName("one entry is always returned, or a single oversized entry would stall replication forever")
    void alwaysReturnsAtLeastOneEntry() {
        log.append(entries(1, 1, 5));

        assertThat(log.entriesFrom(1, 100, 1)).hasSize(1);
    }

    @Test
    void readingPastTheEndReturnsNothing() {
        log.append(entries(1, 1, 2));

        assertThat(log.entriesFrom(3, 10, Long.MAX_VALUE)).isEmpty();
    }

    @Test
    void reportsAnUnknownIndexClearly() {
        log.append(entries(1, 1, 2));

        assertThatThrownBy(() -> log.termAt(9))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("the log ends at index 2");
    }
}
