/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.sim;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

public final class EventTrace {

    private final int capacity;
    private final Deque<String> lines = new ArrayDeque<>();
    private long recorded;

    public EventTrace(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be at least 1, was " + capacity);
        }
        this.capacity = capacity;
    }

    public void record(long time, String line) {
        if (lines.size() == capacity) {
            lines.removeFirst();
        }
        lines.addLast("t=" + time + " " + line);
        recorded++;
    }

    public long recordedCount() {
        return recorded;
    }

    public List<String> lines() {
        return List.copyOf(new ArrayList<>(lines));
    }

    public String render() {
        StringBuilder builder = new StringBuilder();
        if (recorded > lines.size()) {
            builder.append("... ").append(recorded - lines.size()).append(" earlier events omitted\n");
        }
        lines.forEach(line -> builder.append(line).append('\n'));
        return builder.toString();
    }
}
