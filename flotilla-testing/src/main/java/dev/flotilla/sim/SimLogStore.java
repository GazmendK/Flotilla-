/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.sim;

import dev.flotilla.core.InMemoryLogStore;
import dev.flotilla.core.LogEntry;
import dev.flotilla.core.port.LogStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.LongStream;

public final class SimLogStore implements LogStore {

    private final InMemoryLogStore delegate = new InMemoryLogStore();
    private long syncedThrough;

    @Override
    public long firstIndex() {
        return delegate.firstIndex();
    }

    @Override
    public long lastIndex() {
        return delegate.lastIndex();
    }

    @Override
    public long termAt(long index) {
        return delegate.termAt(index);
    }

    @Override
    public Optional<LogEntry> entryAt(long index) {
        return delegate.entryAt(index);
    }

    @Override
    public List<LogEntry> entriesFrom(long fromInclusive, int maxEntries, long maxBytes) {
        return delegate.entriesFrom(fromInclusive, maxEntries, maxBytes);
    }

    @Override
    public void append(List<LogEntry> entries) {
        delegate.append(entries);
    }

    @Override
    public void truncateSuffixFrom(long fromInclusive) {
        delegate.truncateSuffixFrom(fromInclusive);
        syncedThrough = Math.min(syncedThrough, fromInclusive - 1);
    }

    public void syncThrough(long index) {
        syncedThrough = Math.max(syncedThrough, Math.min(index, delegate.lastIndex()));
    }

    public long syncedThrough() {
        return syncedThrough;
    }

    public void discardUnsynced() {
        delegate.truncateSuffixFrom(syncedThrough + 1);
    }

    public List<LogEntry> snapshotEntries() {
        List<LogEntry> entries = new ArrayList<>();
        LongStream.rangeClosed(Math.max(1, delegate.firstIndex()), delegate.lastIndex())
                .forEach(index -> delegate.entryAt(index).ifPresent(entries::add));
        return List.copyOf(entries);
    }
}
