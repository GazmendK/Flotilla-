/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.sim.invariant;

import dev.flotilla.core.LogEntry;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record LogView(long firstIndex, List<LogEntry> entries) {

    public LogView {
        entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
        if (firstIndex < 1) {
            throw new IllegalArgumentException("firstIndex must be at least 1, was " + firstIndex);
        }
    }

    public static LogView of(List<LogEntry> entries) {
        return new LogView(entries.isEmpty() ? 1 : entries.getFirst().index(), entries);
    }

    public static LogView empty() {
        return new LogView(1, List.of());
    }

    public long snapshotIndex() {
        return firstIndex - 1;
    }

    public long lastIndex() {
        return firstIndex + entries.size() - 1;
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public Optional<LogEntry> at(long index) {
        if (index < firstIndex || index > lastIndex()) {
            return Optional.empty();
        }
        return Optional.of(entries.get((int) (index - firstIndex)));
    }

    @Override
    public String toString() {
        return "LogView[" + firstIndex + ".." + lastIndex() + "]";
    }
}
