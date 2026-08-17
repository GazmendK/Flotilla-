/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.storage;

import dev.flotilla.core.LogEntry;
import dev.flotilla.core.port.LogCompactedException;
import dev.flotilla.core.port.LogStore;
import dev.flotilla.storage.io.FileIo;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class SegmentedLogStore implements LogStore, AutoCloseable {

    private final FileIo io;
    private final Path directory;
    private final StorageConfig config;
    private final List<LogSegment> segments = new ArrayList<>();

    private long discardedBytesOnRecovery;

    private SegmentedLogStore(FileIo io, Path directory, StorageConfig config) {
        this.io = io;
        this.directory = directory;
        this.config = config;
    }

    public static SegmentedLogStore open(StorageDirectory storage, StorageConfig config) {
        Objects.requireNonNull(storage, "storage");
        Objects.requireNonNull(config, "config");
        SegmentedLogStore store = new SegmentedLogStore(storage.io(), storage.path(), config);
        store.recover();
        return store;
    }

    private void recover() {
        List<Path> files = io.listSorted(directory, LogSegment.SUFFIX);
        for (Path file : files) {
            LogSegment segment = LogSegment.open(io, file);
            discardedBytesOnRecovery += segment.discardedBytes();
            if (!segments.isEmpty()) {
                LogSegment previous = segments.getLast();
                long expected = previous.lastIndex() + 1;
                if (segment.firstIndex() != expected) {
                    segment.close();
                    closeAll();
                    throw new CorruptionException("Segment " + file + " starts at index " + segment.firstIndex()
                            + " but the previous segment ends at " + previous.lastIndex()
                            + "; the log has a gap and cannot be trusted");
                }
            }
            segments.add(segment);
        }
        if (segments.isEmpty()) {
            segments.add(LogSegment.create(io, directory, 1));
            io.syncDirectory(directory);
        }
    }

    public long discardedBytesOnRecovery() {
        return discardedBytesOnRecovery;
    }

    public int segmentCount() {
        return segments.size();
    }

    @Override
    public long firstIndex() {
        return segments.getFirst().firstIndex();
    }

    @Override
    public long lastIndex() {
        return segments.getLast().lastIndex();
    }

    @Override
    public long termAt(long index) {
        if (index == 0) {
            return 0;
        }
        if (index < firstIndex()) {
            throw new LogCompactedException(index, firstIndex());
        }
        if (index > lastIndex()) {
            throw new IllegalArgumentException(
                    "No entry at index " + index + "; the log ends at index " + lastIndex() + ".");
        }
        return segmentHolding(index).read(index).term();
    }

    @Override
    public Optional<LogEntry> entryAt(long index) {
        if (index < firstIndex() || index > lastIndex()) {
            return Optional.empty();
        }
        return Optional.of(segmentHolding(index).read(index));
    }

    @Override
    public List<LogEntry> entriesFrom(long fromInclusive, int maxEntries, long maxBytes) {
        if (fromInclusive < firstIndex()) {
            throw new LogCompactedException(fromInclusive, firstIndex());
        }
        if (maxEntries <= 0) {
            throw new IllegalArgumentException("maxEntries must be positive, was " + maxEntries);
        }
        if (fromInclusive > lastIndex()) {
            return List.of();
        }

        List<LogEntry> selected = new ArrayList<>();
        long budget = maxBytes;
        for (long index = fromInclusive; index <= lastIndex() && selected.size() < maxEntries; index++) {
            LogEntry entry = segmentHolding(index).read(index);
            if (!selected.isEmpty() && entry.approximateSizeBytes() > budget) {
                break;
            }
            selected.add(entry);
            budget -= entry.approximateSizeBytes();
        }
        return List.copyOf(selected);
    }

    @Override
    public void append(List<LogEntry> entries) {
        Objects.requireNonNull(entries, "entries");
        if (entries.isEmpty()) {
            return;
        }
        long expected = lastIndex() + 1;
        if (entries.getFirst().index() != expected) {
            throw new IllegalArgumentException("Appended entries must start at index " + expected
                    + " (one past the end of the log), but started at "
                    + entries.getFirst().index()
                    + ". Truncate the conflicting suffix first.");
        }
        for (int i = 1; i < entries.size(); i++) {
            if (entries.get(i).index() != entries.get(i - 1).index() + 1) {
                throw new IllegalArgumentException("Appended entries must be contiguous, but index "
                        + entries.get(i - 1).index() + " is followed by "
                        + entries.get(i).index() + ".");
            }
        }

        for (LogEntry entry : entries) {
            activeSegment().append(entry);
            rollIfFull();
        }
        if (config.fsyncPolicy() == FsyncPolicy.ALWAYS) {
            sync();
        }
    }

    @Override
    public void truncateSuffixFrom(long fromInclusive) {
        if (fromInclusive < firstIndex()) {
            throw new IllegalArgumentException("Cannot truncate from index " + fromInclusive
                    + "; the log starts at index " + firstIndex()
                    + ". Truncating a compacted prefix would discard committed entries.");
        }
        if (fromInclusive > lastIndex()) {
            return;
        }

        while (segments.size() > 1 && segments.getLast().firstIndex() > fromInclusive) {
            LogSegment removed = segments.removeLast();
            removed.close();
            io.delete(removed.path());
        }
        segments.getLast().truncateFrom(fromInclusive);
        if (config.fsyncPolicy() == FsyncPolicy.ALWAYS) {
            sync();
        }
        io.syncDirectory(directory);
    }

    public void sync() {
        segments.getLast().sync();
    }

    @Override
    public void close() {
        closeAll();
    }

    private void closeAll() {
        segments.forEach(LogSegment::close);
        segments.clear();
    }

    private LogSegment activeSegment() {
        return segments.getLast();
    }

    private void rollIfFull() {
        LogSegment active = activeSegment();
        if (active.sizeBytes() < config.maxSegmentBytes()) {
            return;
        }
        active.sync();
        segments.add(LogSegment.create(io, directory, active.lastIndex() + 1));
        io.syncDirectory(directory);
    }

    private LogSegment segmentHolding(long index) {
        for (int i = segments.size() - 1; i >= 0; i--) {
            LogSegment segment = segments.get(i);
            if (index >= segment.firstIndex() && index <= segment.lastIndex()) {
                return segment;
            }
        }
        throw new IllegalArgumentException("No segment holds index " + index + " in " + directory);
    }
}
