/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.storage;

import dev.flotilla.core.LogEntry;
import dev.flotilla.core.port.LogCompactedException;
import dev.flotilla.storage.io.FileIo;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class SegmentedLogStore implements DurableLogStore {

    private final FileIo io;
    private final Path directory;
    private final StorageConfig config;
    private final List<LogSegment> segments = new ArrayList<>();
    private final LogBase base;

    private long discardedBytesOnRecovery;
    private int unfinishedSegments;
    private int compactedSegments;

    private SegmentedLogStore(FileIo io, Path directory, StorageConfig config, LogBase base) {
        this.io = io;
        this.directory = directory;
        this.config = config;
        this.base = base;
    }

    public static SegmentedLogStore open(StorageDirectory storage, StorageConfig config) {
        Objects.requireNonNull(storage, "storage");
        Objects.requireNonNull(config, "config");
        LogBase base = LogBase.open(storage.io(), storage.path());
        SegmentedLogStore store = new SegmentedLogStore(storage.io(), storage.path(), config, base);
        store.recover();
        return store;
    }

    private void recover() {
        long baseIndex = base.index();
        boolean deleted = false;
        List<Path> files = io.listSorted(directory, LogSegment.SUFFIX);
        for (Path file : files) {
            Optional<LogSegment> opened = LogSegment.open(io, file);
            if (opened.isEmpty()) {
                unfinishedSegments++;
                io.delete(file);
                deleted = true;
                continue;
            }
            LogSegment segment = opened.get();
            discardedBytesOnRecovery += segment.discardedBytes();
            if (segment.firstIndex() <= baseIndex && segment.lastIndex() <= baseIndex) {
                segment.close();
                io.delete(file);
                compactedSegments++;
                deleted = true;
                continue;
            }
            if (segments.isEmpty() && segment.firstIndex() > baseIndex + 1) {
                segment.close();
                closeAll();
                throw new CorruptionException("Segment " + file + " starts at index " + segment.firstIndex()
                        + " but the log is compacted only through " + baseIndex
                        + "; the entries in between are missing and the log cannot be trusted");
            }
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
            segments.add(LogSegment.create(io, directory, baseIndex + 1));
            deleted = true;
        }
        if (deleted) {
            io.syncDirectory(directory);
        }
    }

    public long discardedBytesOnRecovery() {
        return discardedBytesOnRecovery;
    }

    public int unfinishedSegmentsOnRecovery() {
        return unfinishedSegments;
    }

    public int segmentCount() {
        return segments.size();
    }

    public int compactedSegmentsOnRecovery() {
        return compactedSegments;
    }

    @Override
    public long firstIndex() {
        return base.index() + 1;
    }

    @Override
    public long lastIndex() {
        return segments.getLast().lastIndex();
    }

    @Override
    public long termAt(long index) {
        if (index == base.index()) {
            return base.term();
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

        boolean removedSegments = false;
        while (segments.size() > 1 && segments.getLast().firstIndex() > fromInclusive) {
            LogSegment removed = segments.removeLast();
            removed.close();
            io.delete(removed.path());
            removedSegments = true;
        }
        if (removedSegments) {
            io.syncDirectory(directory);
        }
        segments.getLast().truncateFrom(fromInclusive);
        if (config.fsyncPolicy() == FsyncPolicy.ALWAYS) {
            sync();
        }
    }

    @Override
    public void compactTo(long index) {
        long current = base.index();
        if (index < current) {
            throw new IllegalArgumentException(
                    "Cannot compact to index " + index + "; the log is already compacted through " + current + ".");
        }
        if (index > lastIndex()) {
            throw new IllegalArgumentException(
                    "Cannot compact to index " + index + "; the log ends at index " + lastIndex() + ".");
        }
        if (index == current) {
            return;
        }
        base.update(index, termAt(index));

        boolean removedSegments = false;
        while (segments.size() > 1 && segments.getFirst().lastIndex() <= index) {
            LogSegment removed = segments.removeFirst();
            removed.close();
            io.delete(removed.path());
            removedSegments = true;
        }
        if (removedSegments) {
            io.syncDirectory(directory);
        }
    }

    @Override
    public void resetTo(long index, long term) {
        if (index < base.index()) {
            throw new IllegalArgumentException("Cannot reset to index " + index
                    + "; that would move the snapshot point back from " + base.index() + ".");
        }
        if (term < 0) {
            throw new IllegalArgumentException("term must not be negative, was " + term);
        }
        truncateSuffixFrom(firstIndex());
        sync();
        base.update(index, term);

        for (LogSegment segment : segments) {
            segment.close();
            io.delete(segment.path());
        }
        segments.clear();
        segments.add(LogSegment.create(io, directory, index + 1));
        io.syncDirectory(directory);
    }

    @Override
    public void sync() {
        segments.getLast().sync();
    }

    @Override
    public void close() {
        closeAll();
        base.close();
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
