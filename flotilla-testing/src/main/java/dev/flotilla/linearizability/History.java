/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.linearizability;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

public final class History<I, O> {

    private enum Status {
        PENDING,
        OK,
        FAILED,
        INFO
    }

    private static final class Entry<I, O> {
        private final long process;
        private final I input;
        private final long call;
        private Status status = Status.PENDING;

        @Nullable
        private O output;

        private long returned = Operation.NEVER;

        private Entry(long process, I input, long call) {
            this.process = process;
            this.input = input;
            this.call = call;
        }
    }

    private final List<Entry<I, O>> entries = new ArrayList<>();

    public synchronized int invoke(long process, I input, long time) {
        entries.add(new Entry<>(process, Objects.requireNonNull(input, "input"), time));
        return entries.size() - 1;
    }

    public synchronized void ok(int operation, O output, long time) {
        Entry<I, O> entry = pending(operation, time);
        entry.status = Status.OK;
        entry.output = Objects.requireNonNull(output, "output");
        entry.returned = time;
    }

    public synchronized void fail(int operation, long time) {
        pending(operation, time).status = Status.FAILED;
    }

    public synchronized void info(int operation, long time) {
        pending(operation, time).status = Status.INFO;
    }

    public synchronized int size() {
        return entries.size();
    }

    public synchronized List<Operation<I, O>> operations() {
        List<Operation<I, O>> operations = new ArrayList<>();
        for (int id = 0; id < entries.size(); id++) {
            Entry<I, O> entry = entries.get(id);
            switch (entry.status) {
                case FAILED -> {}
                case OK ->
                    operations.add(Operation.completed(
                            id,
                            entry.process,
                            entry.input,
                            Objects.requireNonNull(entry.output),
                            entry.call,
                            entry.returned));
                case PENDING, INFO ->
                    operations.add(Operation.indeterminate(id, entry.process, entry.input, entry.call));
            }
        }
        return List.copyOf(operations);
    }

    private Entry<I, O> pending(int operation, long time) {
        if (operation < 0 || operation >= entries.size()) {
            throw new IllegalArgumentException("No operation " + operation + " was invoked");
        }
        Entry<I, O> entry = entries.get(operation);
        if (entry.status != Status.PENDING) {
            throw new IllegalStateException("Operation " + operation + " already completed as " + entry.status);
        }
        if (time < entry.call) {
            throw new IllegalArgumentException("Operation " + operation + " completed at " + time
                    + " before it was invoked at " + entry.call + "; the clock is not monotonic");
        }
        return entry;
    }
}
