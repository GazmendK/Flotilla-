/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.sim;

import dev.flotilla.core.Bytes;
import dev.flotilla.core.LogEntry;
import dev.flotilla.core.port.LogStore;
import java.util.Optional;

public final class SimStateMachine {

    private long lastApplied;
    private long digest;

    public long lastApplied() {
        return lastApplied;
    }

    public long digest() {
        return digest;
    }

    public void apply(LogEntry entry) {
        if (entry.index() <= lastApplied) {
            return;
        }
        if (entry.index() != lastApplied + 1) {
            throw new IllegalStateException("Cannot apply index " + entry.index() + " after " + lastApplied
                    + "; the state machine would skip an entry it can never get back.");
        }
        digest = mix(digest, entry);
        lastApplied = entry.index();
    }

    public void restore(long index, long restoredDigest) {
        lastApplied = index;
        digest = restoredDigest;
    }

    public void recover(Optional<Long> snapshotIndex, Optional<Long> snapshotDigest, LogStore log, long commitIndex) {
        lastApplied = 0;
        digest = 0;
        if (snapshotIndex.isPresent() && snapshotDigest.isPresent()) {
            restore(snapshotIndex.get(), snapshotDigest.get());
        }
        long through = Math.min(commitIndex, log.lastIndex());
        for (long index = Math.max(lastApplied + 1, log.firstIndex()); index <= through; index++) {
            log.entryAt(index).ifPresent(this::apply);
        }
    }

    public Bytes capture() {
        return Bytes.ofUtf8(Long.toString(digest));
    }

    public static long digestOf(Bytes data) {
        return Long.parseLong(data.toUtf8());
    }

    private static long mix(long previous, LogEntry entry) {
        long value = previous * 1_000_003L + entry.index();
        value = value * 31 + entry.term();
        value = value * 31 + entry.type().name().hashCode();
        return value * 31 + entry.data().hashCode();
    }

    @Override
    public String toString() {
        return "SimStateMachine[applied=" + lastApplied + " digest=" + digest + "]";
    }
}
