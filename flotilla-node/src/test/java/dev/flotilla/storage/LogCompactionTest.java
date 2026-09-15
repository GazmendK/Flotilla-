/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.storage;

import static org.assertj.core.api.Assertions.assertThat;

import dev.flotilla.core.Bytes;
import dev.flotilla.core.LogEntry;
import dev.flotilla.storage.io.RealFileIo;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LogCompactionTest {

    @TempDir
    Path directory;

    private SegmentedLogStore open() {
        StorageConfig config =
                StorageConfig.of(directory).withFsyncPolicy(FsyncPolicy.ALWAYS).withMaxSegmentBytes(1024);
        return SegmentedLogStore.open(StorageDirectory.open(new RealFileIo(), directory), config);
    }

    private static List<LogEntry> entries(long term, long fromIndex, int count) {
        List<LogEntry> entries = new ArrayList<>();
        for (long index = fromIndex; index < fromIndex + count; index++) {
            entries.add(LogEntry.normal(term, index, Bytes.ofUtf8("value-" + index + "-" + "x".repeat(100))));
        }
        return entries;
    }

    private long segmentFiles() throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(file -> file.getFileName().toString().endsWith(LogSegment.SUFFIX))
                    .count();
        }
    }

    @Test
    @DisplayName("compaction survives a restart, and the segments below the snapshot point are gone")
    void compactionIsDurable() throws IOException {
        long before;
        try (SegmentedLogStore log = open()) {
            log.append(entries(2, 1, 60));
            before = segmentFiles();

            log.compactTo(45);

            assertThat(segmentFiles()).isLessThan(before);
        }

        try (SegmentedLogStore reopened = open()) {
            assertThat(reopened.firstIndex()).isEqualTo(46);
            assertThat(reopened.lastIndex()).isEqualTo(60);
            assertThat(reopened.termAt(45)).isEqualTo(2);
            assertThat(reopened.entriesFrom(46, 100, Long.MAX_VALUE)).isEqualTo(entries(2, 46, 15));
        }
    }

    @Test
    @DisplayName("a reset to a snapshot survives a restart and none of the old log comes back")
    void resetIsDurable() {
        try (SegmentedLogStore log = open()) {
            log.append(entries(1, 1, 30));

            log.resetTo(500, 9);
            log.append(entries(9, 501, 3));
        }

        try (SegmentedLogStore reopened = open()) {
            assertThat(reopened.firstIndex()).isEqualTo(501);
            assertThat(reopened.lastIndex()).isEqualTo(503);
            assertThat(reopened.termAt(500)).isEqualTo(9);
            assertThat(reopened.entriesFrom(501, 10, Long.MAX_VALUE)).isEqualTo(entries(9, 501, 3));
        }
    }

    @Test
    void compactingTheWholeLogAndReopeningResumesAfterIt() {
        try (SegmentedLogStore log = open()) {
            log.append(entries(4, 1, 20));
            log.compactTo(20);
        }

        try (SegmentedLogStore reopened = open()) {
            assertThat(reopened.isEmpty()).isTrue();
            assertThat(reopened.termAt(20)).isEqualTo(4);
            reopened.append(entries(5, 21, 1));
            assertThat(reopened.lastIndex()).isEqualTo(21);
        }
    }
}
