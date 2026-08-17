/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.storage;

import dev.flotilla.core.Bytes;
import dev.flotilla.core.EntryType;
import dev.flotilla.core.LogEntry;
import java.nio.ByteBuffer;
import java.util.zip.CRC32C;

public final class RecordCodec {

    public static final int FRAME_BYTES = 8;
    public static final int MAX_BODY_BYTES = 64 * 1024 * 1024;

    private static final byte RECORD_LOG_ENTRY = 1;
    private static final byte ENTRY_NORMAL = 1;
    private static final byte ENTRY_NOOP = 2;
    private static final byte ENTRY_CONFIGURATION = 3;

    private RecordCodec() {}

    public static byte[] encode(LogEntry entry) {
        byte[] data = entry.data().toByteArray();
        int bodyLength = 1 + Long.BYTES + Long.BYTES + 1 + Integer.BYTES + data.length;

        ByteBuffer buffer = ByteBuffer.allocate(FRAME_BYTES + bodyLength);
        buffer.putInt(bodyLength);
        buffer.putInt(0);
        buffer.put(RECORD_LOG_ENTRY);
        buffer.putLong(entry.term());
        buffer.putLong(entry.index());
        buffer.put(entryTypeCode(entry.type()));
        buffer.putInt(data.length);
        buffer.put(data);

        byte[] bytes = buffer.array();
        ByteBuffer.wrap(bytes).putInt(Integer.BYTES, checksum(bytes, FRAME_BYTES, bodyLength));
        return bytes;
    }

    public static int declaredBodyLength(byte[] frame) {
        int length = ByteBuffer.wrap(frame).getInt(0);
        return length <= 0 || length > MAX_BODY_BYTES ? -1 : length;
    }

    public static boolean checksumMatches(byte[] frame, byte[] body) {
        return ByteBuffer.wrap(frame).getInt(Integer.BYTES) == checksum(body, 0, body.length);
    }

    public static LogEntry decode(byte[] body, Object file, long offset) {
        ByteBuffer buffer = ByteBuffer.wrap(body);
        byte recordType = buffer.get();
        if (recordType != RECORD_LOG_ENTRY) {
            throw CorruptionException.at(file, offset, "unknown record type " + recordType);
        }
        long term = buffer.getLong();
        long index = buffer.getLong();
        EntryType entryType = entryType(buffer.get(), file, offset);
        int dataLength = buffer.getInt();
        if (dataLength < 0 || dataLength > buffer.remaining()) {
            throw CorruptionException.at(file, offset, "payload length " + dataLength + " exceeds the record");
        }
        byte[] data = new byte[dataLength];
        buffer.get(data);
        return new LogEntry(term, index, entryType, Bytes.wrap(data));
    }

    private static int checksum(byte[] bytes, int offset, int length) {
        CRC32C crc = new CRC32C();
        crc.update(bytes, offset, length);
        return (int) crc.getValue();
    }

    private static byte entryTypeCode(EntryType type) {
        return switch (type) {
            case NORMAL -> ENTRY_NORMAL;
            case NOOP -> ENTRY_NOOP;
            case CONFIGURATION -> ENTRY_CONFIGURATION;
        };
    }

    private static EntryType entryType(byte code, Object file, long offset) {
        return switch (code) {
            case ENTRY_NORMAL -> EntryType.NORMAL;
            case ENTRY_NOOP -> EntryType.NOOP;
            case ENTRY_CONFIGURATION -> EntryType.CONFIGURATION;
            default -> throw CorruptionException.at(file, offset, "unknown entry type " + code);
        };
    }
}
