/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.core.port;

public final class LogCompactedException extends RuntimeException {
    private final long requestedIndex;
    private final long firstAvailableIndex;

    public LogCompactedException(long requestedIndex, long firstAvailableIndex) {
        super("Log entry at index " + requestedIndex + " has been compacted; the oldest available "
                + "entry is at index " + firstAvailableIndex + ". Send a snapshot instead.");
        this.requestedIndex = requestedIndex;
        this.firstAvailableIndex = firstAvailableIndex;
    }

    public long requestedIndex() {
        return requestedIndex;
    }

    public long firstAvailableIndex() {
        return firstAvailableIndex;
    }
}
