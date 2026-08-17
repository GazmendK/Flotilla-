/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.flotilla.core.Bytes;
import dev.flotilla.core.LogEntry;
import dev.flotilla.storage.io.RealFileIo;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.List;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SegmentedLogStoreTest {

    @TempDir
    Path directory;

    private StorageConfig config = StorageConfig.of(Path.of("."));

    private SegmentedLogStore open() {
        config = StorageConfig.of(directory).withFsyncPolicy(FsyncPolicy.NEVER).withMaxSegmentBytes(4096);
        return SegmentedLogStore.open(StorageDirectory.open(new RealFileIo(), directory), config);
    }

    private static List<LogEntry> entries(long term, long from, int count) {
        return LongStream.range(0, count)
                .mapToObj(offset -> LogEntry.normal(term, from + offset, Bytes.ofUtf8("value-" + (from + offset))))
                .toList();
    }

    private List<Path> segmentFiles() throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(path -> path.getFileName().toString().endsWith(".wal"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        }
    }

    @Test
    @DisplayName("entries survive closing and reopening the store")
    void entriesSurviveAReopen() {
        try (SegmentedLogStore store = open()) {
            store.append(entries(3, 1, 40));
            store.sync();
        }

        try (SegmentedLogStore reopened = open()) {
            assertThat(reopened.lastIndex()).isEqualTo(40);
            assertThat(reopened.termAt(40)).isEqualTo(3);
            assertThat(reopened.entryAt(17)).map(LogEntry::data).contains(Bytes.ofUtf8("value-17"));
        }
    }

    @Test
    @DisplayName("the log rolls into new segment files instead of growing one forever")
    void theLogRollsIntoSegments() throws IOException {
        try (SegmentedLogStore store = open()) {
            store.append(entries(1, 1, 400));
            store.sync();

            assertThat(store.segmentCount()).isGreaterThan(1);
        }
        assertThat(segmentFiles()).hasSizeGreaterThan(1);
    }

    @Test
    @DisplayName("a log spanning several segments reads back as one contiguous sequence")
    void readsSpanSegmentBoundaries() {
        try (SegmentedLogStore store = open()) {
            store.append(entries(1, 1, 400));
            store.sync();
        }
        try (SegmentedLogStore reopened = open()) {
            assertThat(reopened.segmentCount()).isGreaterThan(1);
            assertThat(reopened.entriesFrom(1, 400, Long.MAX_VALUE))
                    .extracting(LogEntry::index)
                    .containsExactlyElementsOf(
                            LongStream.rangeClosed(1, 400).boxed().toList());
        }
    }

    @Test
    @DisplayName("truncation deletes the segments it emptied")
    void truncationRemovesWholeSegments() throws IOException {
        try (SegmentedLogStore store = open()) {
            store.append(entries(1, 1, 400));
            store.sync();
            int before = store.segmentCount();

            store.truncateSuffixFrom(20);

            assertThat(store.lastIndex()).isEqualTo(19);
            assertThat(store.segmentCount()).isLessThan(before);
        }
        try (SegmentedLogStore reopened = open()) {
            assertThat(reopened.lastIndex()).isEqualTo(19);
        }
    }

    @Test
    @DisplayName("a half-written record at the end of the log is discarded on recovery, not treated as an error")
    void aTornTailIsTruncatedOnRecovery() throws IOException {
        try (SegmentedLogStore store = open()) {
            store.append(entries(2, 1, 10));
            store.sync();
        }

        Path segment = segmentFiles().getLast();
        long sizeBefore = Files.size(segment);
        byte[] halfRecord = {0, 0, 0, 40, 12, 34, 56};
        Files.write(segment, halfRecord, StandardOpenOption.APPEND);

        try (SegmentedLogStore recovered = open()) {
            assertThat(recovered.lastIndex()).isEqualTo(10);
            assertThat(recovered.discardedBytesOnRecovery()).isEqualTo(halfRecord.length);
        }
        assertThat(Files.size(segment)).isEqualTo(sizeBefore);
    }

    @Test
    @DisplayName("trailing zeroes read as the end of the log, which is what preallocation leaves behind")
    void trailingZeroesEndTheLog() throws IOException {
        try (SegmentedLogStore store = open()) {
            store.append(entries(2, 1, 5));
            store.sync();
        }

        Path segment = segmentFiles().getLast();
        Files.write(segment, new byte[512], StandardOpenOption.APPEND);

        try (SegmentedLogStore recovered = open()) {
            assertThat(recovered.lastIndex()).isEqualTo(5);
            assertThat(recovered.discardedBytesOnRecovery()).isEqualTo(512);
        }
    }

    @Test
    @DisplayName("a corrupted record body is discarded along with everything after it")
    void aCorruptedRecordEndsTheLog() throws IOException {
        try (SegmentedLogStore store = open()) {
            store.append(entries(2, 1, 20));
            store.sync();
        }

        Path segment = segmentFiles().getFirst();
        byte[] content = Files.readAllBytes(segment);
        content[content.length - 3] ^= 0x40;
        Files.write(segment, content);

        try (SegmentedLogStore recovered = open()) {
            assertThat(recovered.lastIndex()).isLessThan(20);
            assertThat(recovered.discardedBytesOnRecovery()).isPositive();
        }
    }

    @Test
    @DisplayName("a file that is not a segment is refused rather than misread")
    void aForeignFileIsRefused() throws IOException {
        Files.writeString(directory.resolve("00000000000000000001.wal"), "not a segment at all, really");

        assertThatThrownBy(this::open)
                .isInstanceOf(CorruptionException.class)
                .hasMessageContaining("not a Flotilla write-ahead log segment");
    }

    @Test
    @DisplayName("a gap between segments is a hard failure, because it can only mean lost data")
    void aGapBetweenSegmentsIsRefused() throws IOException {
        try (SegmentedLogStore store = open()) {
            store.append(entries(1, 1, 400));
            store.sync();
            assertThat(store.segmentCount()).isGreaterThan(2);
        }

        List<Path> files = segmentFiles();
        Files.delete(files.get(1));

        assertThatThrownBy(this::open).isInstanceOf(CorruptionException.class).hasMessageContaining("gap");
    }

    @Test
    @DisplayName("reopening an untouched log discards nothing")
    void aCleanReopenDiscardsNothing() {
        try (SegmentedLogStore store = open()) {
            store.append(entries(1, 1, 30));
            store.sync();
        }
        try (SegmentedLogStore reopened = open()) {
            assertThat(reopened.discardedBytesOnRecovery()).isZero();
            assertThat(reopened.lastIndex()).isEqualTo(30);
        }
    }
}
