/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.flotilla.core.Bytes;
import dev.flotilla.core.LogEntry;
import dev.flotilla.core.port.LogCompactedException;
import dev.flotilla.core.port.LogStore;
import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

abstract class LogStoreContractTest {

    private LogStore log;

    protected abstract LogStore createStore();

    @BeforeEach
    void createLog() {
        log = createStore();
    }

    protected final LogStore log() {
        return log;
    }

    protected static List<LogEntry> entries(long term, long fromIndex, int count) {
        return LongStream.range(0, count)
                .mapToObj(offset -> LogEntry.normal(term, fromIndex + offset, Bytes.ofUtf8("v" + (fromIndex + offset))))
                .toList();
    }

    @Test
    @DisplayName("an empty log reports first=1, last=0")
    void emptyLogUsesTheOneBasedConvention() {
        assertThat(log.firstIndex()).isEqualTo(1);
        assertThat(log.lastIndex()).isZero();
        assertThat(log.isEmpty()).isTrue();
        assertThat(log.entryAt(1)).isEmpty();
    }

    @Test
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
    void preservesPayloadsExactly() {
        byte[] payload = {0, 1, 2, (byte) 0xFF, (byte) 0x80, 127};
        log.append(List.of(LogEntry.normal(4, 1, Bytes.copyOf(payload))));

        assertThat(log.entryAt(1).orElseThrow().data().toByteArray()).containsExactly(payload);
    }

    @Test
    void rejectsAppendThatDoesNotContinueTheLog() {
        log.append(entries(1, 1, 3));

        assertThatThrownBy(() -> log.append(entries(2, 3, 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must start at index 4");
    }

    @Test
    void rejectsNonContiguousBatches() {
        assertThatThrownBy(() -> log.append(List.of(LogEntry.noOp(1, 1), LogEntry.noOp(1, 3))))
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
    void truncatingEverythingLeavesAnEmptyLog() {
        log.append(entries(1, 1, 4));

        log.truncateSuffixFrom(1);

        assertThat(log.isEmpty()).isTrue();
        assertThat(log.lastIndex()).isZero();
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

    @Test
    void holdsManyEntriesInOrder() {
        log.append(entries(1, 1, 500));

        assertThat(log.lastIndex()).isEqualTo(500);
        assertThat(log.entryAt(250)).map(LogEntry::data).contains(Bytes.ofUtf8("v250"));
        assertThat(log.entriesFrom(1, 500, Long.MAX_VALUE))
                .extracting(LogEntry::index)
                .containsExactlyElementsOf(
                        LongStream.rangeClosed(1, 500).boxed().toList());
    }

    @Test
    @DisplayName("after compaction the term of the last compacted entry is still known")
    void compactionKeepsTheTermAtTheSnapshotPoint() {
        log.append(entries(1, 1, 3));
        log.append(entries(2, 4, 3));

        log.compactTo(4);

        assertThat(log.firstIndex()).isEqualTo(5);
        assertThat(log.lastIndex()).isEqualTo(6);
        assertThat(log.termAt(4))
                .as("the leader needs this term as prevLogTerm for the next append after a snapshot")
                .isEqualTo(2);
        assertThat(log.termAt(5)).isEqualTo(2);
    }

    @Test
    @DisplayName("reading below the compacted prefix is a defined error, never a wrong entry")
    void readingACompactedIndexIsReportedPrecisely() {
        log.append(entries(1, 1, 6));

        log.compactTo(4);

        assertThatThrownBy(() -> log.termAt(3)).isInstanceOfSatisfying(LogCompactedException.class, failure -> {
            assertThat(failure.requestedIndex()).isEqualTo(3);
            assertThat(failure.firstAvailableIndex()).isEqualTo(5);
        });
        assertThatThrownBy(() -> log.entriesFrom(2, 10, Long.MAX_VALUE)).isInstanceOf(LogCompactedException.class);
        assertThat(log.entryAt(4)).isEmpty();
        assertThat(log.entriesFrom(5, 10, Long.MAX_VALUE))
                .extracting(LogEntry::index)
                .containsExactly(5L, 6L);
    }

    @Test
    void appendingContinuesAfterCompaction() {
        log.append(entries(1, 1, 3));
        log.compactTo(3);

        log.append(entries(2, 4, 2));

        assertThat(log.lastIndex()).isEqualTo(5);
        assertThat(log.termAt(3)).isEqualTo(1);
        assertThat(log.entryAt(5)).map(LogEntry::term).contains(2L);
    }

    @Test
    @DisplayName("compacting everything leaves an empty log that still knows where it stands")
    void compactingTheWholeLogKeepsItAnchored() {
        log.append(entries(3, 1, 3));

        log.compactTo(3);

        assertThat(log.isEmpty()).isTrue();
        assertThat(log.firstIndex()).isEqualTo(4);
        assertThat(log.lastIndex()).isEqualTo(3);
        assertThat(log.termAt(3)).isEqualTo(3);
        log.append(entries(4, 4, 1));
        assertThat(log.lastIndex()).isEqualTo(4);
    }

    @Test
    void compactionCannotMoveBackwardsOrPastTheEnd() {
        log.append(entries(1, 1, 5));
        log.compactTo(3);

        log.compactTo(3);

        assertThatThrownBy(() -> log.compactTo(2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> log.compactTo(9)).isInstanceOf(IllegalArgumentException.class);
        assertThat(log.firstIndex()).isEqualTo(4);
    }

    @Test
    @DisplayName("a divergent suffix can still be truncated, but never into the compacted prefix")
    void truncationStopsAtTheCompactedPrefix() {
        log.append(entries(1, 1, 6));
        log.compactTo(3);

        assertThatThrownBy(() -> log.truncateSuffixFrom(3)).isInstanceOf(IllegalArgumentException.class);
        log.truncateSuffixFrom(5);

        assertThat(log.lastIndex()).isEqualTo(4);
        assertThat(log.firstIndex()).isEqualTo(4);
    }

    @Test
    @DisplayName("resetting to a snapshot discards the whole log and resumes right after the snapshot")
    void resetDiscardsTheWholeLog() {
        log.append(entries(1, 1, 5));

        log.resetTo(20, 7);

        assertThat(log.firstIndex()).isEqualTo(21);
        assertThat(log.lastIndex()).isEqualTo(20);
        assertThat(log.isEmpty()).isTrue();
        assertThat(log.termAt(20)).isEqualTo(7);
        assertThat(log.entryAt(3)).isEmpty();
        assertThatThrownBy(() -> log.termAt(5)).isInstanceOf(LogCompactedException.class);
        log.append(entries(8, 21, 2));
        assertThat(log.lastIndex()).isEqualTo(22);
    }

    @Test
    void resetCannotMoveTheSnapshotPointBackwards() {
        log.append(entries(1, 1, 5));
        log.compactTo(4);

        assertThatThrownBy(() -> log.resetTo(3, 1)).isInstanceOf(IllegalArgumentException.class);
    }
}
