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

    public static final byte FORMAT_VERSION = 1;

    private static final int MAX_KEYS = 100_000_000;

    private KvSnapshotCodec() {}

    public static Bytes encode(long lastAppliedIndex, NavigableMap<Bytes, Bytes> data) {
        Objects.requireNonNull(data, "data");
        int size = 1 + Long.BYTES + Integer.BYTES;
        for (Map.Entry<Bytes, Bytes> entry : data.entrySet()) {
            size += Integer.BYTES
                    + entry.getKey().size()
                    + Integer.BYTES
                    + entry.getValue().size();
        }

        ByteBuffer buffer = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN);
        buffer.put(FORMAT_VERSION);
        buffer.putLong(lastAppliedIndex);
        buffer.putInt(data.size());
        for (Map.Entry<Bytes, Bytes> entry : data.entrySet()) {
            putField(buffer, entry.getKey());
            putField(buffer, entry.getValue());
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
            int keys = buffer.getInt();
            if (keys < 0 || keys > MAX_KEYS) {
                throw new MalformedCommandException("Snapshot declares " + keys + " keys, outside 0.." + MAX_KEYS);
            }
            NavigableMap<Bytes, Bytes> data = new TreeMap<>();
            for (int i = 0; i < keys; i++) {
                data.put(field(buffer), field(buffer));
            }
            if (buffer.hasRemaining()) {
                throw new MalformedCommandException(buffer.remaining() + " trailing bytes after the snapshot");
            }
            return new Snapshot(lastAppliedIndex, data);
        } catch (BufferUnderflowException truncated) {
            throw new MalformedCommandException("Snapshot ends in the middle of a field", truncated);
        }
    }

    private static void putField(ByteBuffer buffer, Bytes field) {
        buffer.putInt(field.size());
        buffer.put(field.toByteArray());
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

    public record Snapshot(long lastAppliedIndex, NavigableMap<Bytes, Bytes> data) {}
}
