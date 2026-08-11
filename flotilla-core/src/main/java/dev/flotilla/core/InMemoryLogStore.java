/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.core;

import dev.flotilla.core.port.LogCompactedException;
import dev.flotilla.core.port.LogStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class InMemoryLogStore implements LogStore {
    private final List<LogEntry> entries = new ArrayList<>();

    private long firstIndex = 1;

    @Override
    public long firstIndex() {
        return firstIndex;
    }

    @Override
    public long lastIndex() {
        return firstIndex + entries.size() - 1;
    }

    @Override
    public long termAt(long index) {
        if (index == 0) {
            return 0;
        }
        if (index < firstIndex) {
            throw new LogCompactedException(index, firstIndex);
        }
        if (index > lastIndex()) {
            throw new IllegalArgumentException(
                    "No entry at index " + index + "; the log ends at index " + lastIndex() + ".");
        }
        return entries.get((int) (index - firstIndex)).term();
    }

    @Override
    public Optional<LogEntry> entryAt(long index) {
        if (index < firstIndex || index > lastIndex()) {
            return Optional.empty();
        }
        return Optional.of(entries.get((int) (index - firstIndex)));
    }

    @Override
    public List<LogEntry> entriesFrom(long fromInclusive, int maxEntries, long maxBytes) {
        if (fromInclusive < firstIndex) {
            throw new LogCompactedException(fromInclusive, firstIndex);
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
            LogEntry entry = entries.get((int) (index - firstIndex));

            if (!selected.isEmpty() && entry.approximateSizeBytes() > budget) {
                break;
            }
            selected.add(entry);
            budget -= entry.approximateSizeBytes();
        }
        return List.copyOf(selected);
    }

    @Override
    public void append(List<LogEntry> newEntries) {
        Objects.requireNonNull(newEntries, "newEntries");
        if (newEntries.isEmpty()) {
            return;
        }

        long expected = lastIndex() + 1;
        if (newEntries.getFirst().index() != expected) {
            throw new IllegalArgumentException("Appended entries must start at index " + expected
                    + " (one past the end of the log), but started at "
                    + newEntries.getFirst().index()
                    + ". Truncate the conflicting suffix first.");
        }
        for (int i = 1; i < newEntries.size(); i++) {
            long previous = newEntries.get(i - 1).index();
            long current = newEntries.get(i).index();
            if (current != previous + 1) {
                throw new IllegalArgumentException("Appended entries must be contiguous, but index " + previous
                        + " is followed by " + current + ".");
            }
        }
        entries.addAll(newEntries);
    }

    @Override
    public void truncateSuffixFrom(long fromInclusive) {
        if (fromInclusive < firstIndex) {
            throw new IllegalArgumentException("Cannot truncate from index " + fromInclusive
                    + "; the log starts at index " + firstIndex
                    + ". Truncating a compacted prefix would discard committed entries.");
        }
        if (fromInclusive > lastIndex()) {
            return;
        }
        entries.subList((int) (fromInclusive - firstIndex), entries.size()).clear();
    }

    @Override
    public String toString() {
        return "InMemoryLogStore[" + firstIndex + ".." + lastIndex() + "]";
    }
}
