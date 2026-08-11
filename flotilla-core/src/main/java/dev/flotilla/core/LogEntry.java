/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.core;

import java.util.Objects;

@RaftSpec("§5.3 Log replication")
public record LogEntry(long term, long index, EntryType type, Bytes data) {
    public LogEntry {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(data, "data");
        if (term < 0) {
            throw new IllegalArgumentException("term must not be negative, was " + term);
        }
        if (index < 1) {
            throw new IllegalArgumentException(
                    "index must be at least 1 (the log is 1-based; 0 means 'no entry'), was " + index);
        }
    }

    public static LogEntry normal(long term, long index, Bytes data) {
        return new LogEntry(term, index, EntryType.NORMAL, data);
    }

    public static LogEntry noOp(long term, long index) {
        return new LogEntry(term, index, EntryType.NOOP, Bytes.EMPTY);
    }

    public static LogEntry configuration(long term, long index, Bytes data) {
        return new LogEntry(term, index, EntryType.CONFIGURATION, data);
    }

    public int approximateSizeBytes() {
        return Long.BYTES * 2 + 1 + data.size();
    }
}
