/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.client;

import dev.flotilla.kv.Command;
import dev.flotilla.kv.KvResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.jspecify.annotations.Nullable;

public final class HistoryRecorder {

    public enum Type {
        INVOKE,
        OK,
        FAIL,
        INFO
    }

    public record Event(
            long operation,
            long process,
            Type type,
            Command command,
            @Nullable KvResponse response,
            long nanos) {}

    private final LongSupplier clock;
    private final AtomicLong processes = new AtomicLong();
    private final AtomicLong operations = new AtomicLong();
    private final List<Event> events = new ArrayList<>();

    public HistoryRecorder(LongSupplier clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public long newProcess() {
        return processes.incrementAndGet();
    }

    public long invoke(long process, Command command) {
        long operation = operations.incrementAndGet();
        record(operation, process, Type.INVOKE, command, null);
        return operation;
    }

    public void ok(long operation, long process, Command command, KvResponse response) {
        record(operation, process, Type.OK, command, response);
    }

    public void fail(long operation, long process, Command command, @Nullable KvResponse response) {
        record(operation, process, Type.FAIL, command, response);
    }

    public void info(long operation, long process, Command command) {
        record(operation, process, Type.INFO, command, null);
    }

    public synchronized List<Event> events() {
        return List.copyOf(events);
    }

    private synchronized void record(
            long operation, long process, Type type, Command command, @Nullable KvResponse response) {
        events.add(new Event(operation, process, type, command, response, clock.getAsLong()));
    }
}
