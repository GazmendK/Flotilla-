/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.kv;

import dev.flotilla.core.Bytes;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;

public final class KvSnapshotCodec {

    public static final byte FORMAT_VERSION = 2;

    private static final int MAX_KEYS = 100_000_000;
    private static final int MAX_SESSIONS = 10_000_000;

    private KvSnapshotCodec() {}

    public static Bytes encode(
            long lastAppliedIndex, NavigableMap<Bytes, Bytes> data, NavigableMap<Long, Session> sessions) {
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(sessions, "sessions");

        int size = 1 + Long.BYTES + Integer.BYTES + Integer.BYTES;
        for (Map.Entry<Bytes, Bytes> entry : data.entrySet()) {
            size += field(entry.getKey()) + field(entry.getValue());
        }
        for (Session session : sessions.values()) {
            size += Long.BYTES + Long.BYTES + Long.BYTES + field(session.lastResponse());
        }

        ByteBuffer buffer = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN);
        buffer.put(FORMAT_VERSION);
        buffer.putLong(lastAppliedIndex);
        buffer.putInt(data.size());
        for (Map.Entry<Bytes, Bytes> entry : data.entrySet()) {
            putField(buffer, entry.getKey());
            putField(buffer, entry.getValue());
        }
        buffer.putInt(sessions.size());
        for (Map.Entry<Long, Session> entry : sessions.entrySet()) {
            buffer.putLong(entry.getKey());
            buffer.putLong(entry.getValue().lastSequence());
            buffer.putLong(entry.getValue().lastActiveIndex());
            putField(buffer, entry.getValue().lastResponse());
        }
        return Bytes.wrap(buffer.array());
    }

    public static Snapshot decode(Bytes encoded) {
        Objects.requireNonNull(encoded, "encoded");
        ByteBuffer buffer = ByteBuffer.wrap(encoded.toByteArray()).order(ByteOrder.BIG_ENDIAN);
        try {
            byte version = buffer.get();
            if (version != FORMAT_VERSION) {
                throw new MalformedCommandException("Snapshot format version " + version + " is not " + FORMAT_VERSION);
            }
            long lastAppliedIndex = buffer.getLong();

            int keys = bounded(buffer.getInt(), MAX_KEYS, "keys");
            NavigableMap<Bytes, Bytes> data = new TreeMap<>();
            for (int i = 0; i < keys; i++) {
                data.put(field(buffer), field(buffer));
            }

            int sessionCount = bounded(buffer.getInt(), MAX_SESSIONS, "sessions");
            NavigableMap<Long, Session> sessions = new TreeMap<>();
            for (int i = 0; i < sessionCount; i++) {
                long clientId = buffer.getLong();
                long lastSequence = buffer.getLong();
                long lastActiveIndex = buffer.getLong();
                sessions.put(clientId, new Session(lastSequence, field(buffer), lastActiveIndex));
            }

            if (buffer.hasRemaining()) {
                throw new MalformedCommandException(buffer.remaining() + " trailing bytes after the snapshot");
            }
            return new Snapshot(lastAppliedIndex, data, sessions);
        } catch (BufferUnderflowException truncated) {
            throw new MalformedCommandException("Snapshot ends in the middle of a field", truncated);
        }
    }

    private static int bounded(int count, int max, String what) {
        if (count < 0 || count > max) {
            throw new MalformedCommandException("Snapshot declares " + count + " " + what + ", outside 0.." + max);
        }
        return count;
    }

    private static int field(Bytes value) {
        return Integer.BYTES + value.size();
    }

    private static void putField(ByteBuffer buffer, Bytes value) {
        buffer.putInt(value.size());
        buffer.put(value.toByteArray());
    }

    private static Bytes field(ByteBuffer buffer) {
        int length = buffer.getInt();
        if (length < 0 || length > CommandCodec.MAX_FIELD_BYTES) {
            throw new MalformedCommandException(
                    "Field length " + length + " is outside 0.." + CommandCodec.MAX_FIELD_BYTES);
        }
        if (buffer.remaining() < length) {
            throw new MalformedCommandException(
                    "Field declares " + length + " bytes but only " + buffer.remaining() + " remain");
        }
        byte[] field = new byte[length];
        buffer.get(field);
        return Bytes.wrap(field);
    }

    public record Snapshot(
            long lastAppliedIndex, NavigableMap<Bytes, Bytes> data, NavigableMap<Long, Session> sessions) {}
}
