/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.core.port;

import dev.flotilla.core.LogEntry;
import java.util.List;
import java.util.Optional;

public interface LogStore {
    long firstIndex();

    long lastIndex();

    default boolean isEmpty() {
        return lastIndex() < firstIndex();
    }

    long termAt(long index);

    Optional<LogEntry> entryAt(long index);

    List<LogEntry> entriesFrom(long fromInclusive, int maxEntries, long maxBytes);

    void append(List<LogEntry> entries);

    void truncateSuffixFrom(long fromInclusive);
}
