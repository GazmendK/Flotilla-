/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.sim;

import dev.flotilla.core.Bytes;
import dev.flotilla.core.EntryType;
import dev.flotilla.core.LogEntry;
import dev.flotilla.core.Snapshot;
import dev.flotilla.core.port.LogStore;
import java.util.HashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

public final class SimStateMachine {

    public record Applied(String operation, String response) {}

    private static final String NIL = "-";
    private static final String FIELD = "\\|";

    private final NavigableMap<String, String> data = new TreeMap<>();
    private final Map<Long, Applied> watched = new HashMap<>();

    private long lastApplied;
    private long digest;

    public long lastApplied() {
        return lastApplied;
    }

    public long digest() {
        return digest;
    }

    public Optional<String> get(String key) {
        return Optional.ofNullable(data.get(key));
    }

    public void watch(long index) {
        watched.put(index, new Applied("", ""));
    }

    public Optional<Applied> takeApplied(long index) {
        Applied applied = watched.get(index);
        if (applied == null || applied.operation().isEmpty()) {
            return Optional.empty();
        }
        watched.remove(index);
        return Optional.of(applied);
    }

    public static Bytes put(String operation, String key, String value) {
        return Bytes.ofUtf8(operation + "|put|" + key + "|" + value);
    }

    public static Bytes compareAndSwap(
            String operation, String key, @Nullable String expected, @Nullable String value) {
        return Bytes.ofUtf8(operation + "|cas|" + key + "|" + orNil(expected) + "|" + orNil(value));
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
        Applied applied =
                entry.type() == EntryType.NORMAL ? execute(entry.data().toUtf8()) : new Applied("-", "");
        if (watched.containsKey(entry.index())) {
            watched.put(entry.index(), applied);
        }
    }

    private Applied execute(String command) {
        String[] fields = command.split(FIELD, -1);
        if (fields.length < 4) {
            return new Applied("-", "");
        }
        String key = fields[2];
        return switch (fields[1]) {
            case "put" -> new Applied(fields[0], orNil(data.put(key, fields[3])));
            case "cas" -> {
                if (fields.length < 5) {
                    yield new Applied("-", "");
                }
                boolean swapped = Objects.equals(data.get(key), fromNil(fields[3]));
                if (swapped) {
                    String value = fromNil(fields[4]);
                    if (value == null) {
                        data.remove(key);
                    } else {
                        data.put(key, value);
                    }
                }
                yield new Applied(fields[0], Boolean.toString(swapped));
            }
            default -> new Applied("-", "");
        };
    }

    public Bytes capture() {
        StringBuilder out = new StringBuilder().append(digest);
        data.forEach((key, value) -> out.append('\n').append(key).append('=').append(value));
        return Bytes.ofUtf8(out.toString());
    }

    public void restore(long index, Bytes snapshot) {
        String[] lines = snapshot.toUtf8().split("\n", -1);
        digest = Long.parseLong(lines[0]);
        data.clear();
        for (int line = 1; line < lines.length; line++) {
            int separator = lines[line].indexOf('=');
            data.put(lines[line].substring(0, separator), lines[line].substring(separator + 1));
        }
        lastApplied = index;
        watched.clear();
    }

    public void recover(Optional<Snapshot> snapshot, LogStore log, long commitIndex) {
        lastApplied = 0;
        digest = 0;
        data.clear();
        watched.clear();
        snapshot.ifPresent(taken -> restore(taken.lastIncludedIndex(), taken.data()));
        long through = Math.min(commitIndex, log.lastIndex());
        for (long index = Math.max(lastApplied + 1, log.firstIndex()); index <= through; index++) {
            log.entryAt(index).ifPresent(this::apply);
        }
    }

    public static @Nullable String fromNil(String value) {
        return NIL.equals(value) ? null : value;
    }

    private static String orNil(@Nullable String value) {
        return value == null ? NIL : value;
    }

    private static long mix(long previous, LogEntry entry) {
        long value = previous * 1_000_003L + entry.index();
        value = value * 31 + entry.term();
        value = value * 31 + entry.type().name().hashCode();
        return value * 31 + entry.data().hashCode();
    }

    @Override
    public String toString() {
        return "SimStateMachine[applied=" + lastApplied + " keys=" + data.size() + " digest=" + digest + "]";
    }
}
