/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.storage;

import dev.flotilla.core.LogEntry;
import dev.flotilla.storage.io.FileHandle;
import dev.flotilla.storage.io.FileIo;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32C;

final class LogSegment implements AutoCloseable {

    static final int HEADER_BYTES = 24;
    static final String SUFFIX = ".wal";

    private static final long MAGIC = 0x464C4F5457414C31L;
    private static final int FORMAT_VERSION = 1;

    private final FileHandle handle;
    private final long firstIndex;
    private final List<Long> offsets = new ArrayList<>();

    private long writePosition = HEADER_BYTES;
    private long discardedBytes;

    private LogSegment(FileHandle handle, long firstIndex) {
        this.handle = handle;
        this.firstIndex = firstIndex;
    }

    static Path fileName(Path directory, long firstIndex) {
        return directory.resolve(String.format("%020d%s", firstIndex, SUFFIX));
    }

    static LogSegment create(FileIo io, Path directory, long firstIndex) {
        Path path = fileName(directory, firstIndex);
        FileHandle handle = io.open(path);
        handle.truncate(0);
        handle.writeAt(0, header(firstIndex));
        handle.sync();
        return new LogSegment(handle, firstIndex);
    }

    static LogSegment open(FileIo io, Path path) {
        FileHandle handle = io.open(path);
        byte[] header = new byte[HEADER_BYTES];
        if (handle.readAt(0, header) < HEADER_BYTES) {
            handle.close();
            throw CorruptionException.at(path, 0, "segment is shorter than its header");
        }
        ByteBuffer buffer = ByteBuffer.wrap(header);
        if (buffer.getLong() != MAGIC) {
            handle.close();
            throw CorruptionException.at(path, 0, "not a Flotilla write-ahead log segment");
        }
        int version = buffer.getInt();
        if (version != FORMAT_VERSION) {
            handle.close();
            throw CorruptionException.at(path, 8, "unsupported format version " + version);
        }
        long firstIndex = buffer.getLong();
        if (checksum(header, 0, 20) != buffer.getInt()) {
            handle.close();
            throw CorruptionException.at(path, 20, "segment header checksum mismatch");
        }

        LogSegment segment = new LogSegment(handle, firstIndex);
        segment.scan();
        return segment;
    }

    private void scan() {
        long fileSize = handle.size();
        long position = HEADER_BYTES;
        byte[] frame = new byte[RecordCodec.FRAME_BYTES];

        while (position + RecordCodec.FRAME_BYTES <= fileSize) {
            if (handle.readAt(position, frame) < RecordCodec.FRAME_BYTES) {
                break;
            }
            int bodyLength = RecordCodec.declaredBodyLength(frame);
            if (bodyLength < 0 || position + RecordCodec.FRAME_BYTES + bodyLength > fileSize) {
                break;
            }
            byte[] body = new byte[bodyLength];
            if (handle.readAt(position + RecordCodec.FRAME_BYTES, body) < bodyLength) {
                break;
            }
            if (!RecordCodec.checksumMatches(frame, body)) {
                break;
            }
            offsets.add(position);
            position += RecordCodec.FRAME_BYTES + bodyLength;
        }

        discardedBytes = fileSize - position;
        writePosition = position;
        if (discardedBytes > 0) {
            handle.truncate(position);
            handle.sync();
        }
    }

    long firstIndex() {
        return firstIndex;
    }

    long lastIndex() {
        return firstIndex + offsets.size() - 1;
    }

    boolean isEmpty() {
        return offsets.isEmpty();
    }

    int entryCount() {
        return offsets.size();
    }

    long sizeBytes() {
        return writePosition;
    }

    long discardedBytes() {
        return discardedBytes;
    }

    Path path() {
        return handle.path();
    }

    void append(LogEntry entry) {
        byte[] record = RecordCodec.encode(entry);
        handle.writeAt(writePosition, record);
        offsets.add(writePosition);
        writePosition += record.length;
    }

    LogEntry read(long index) {
        int slot = (int) (index - firstIndex);
        if (slot < 0 || slot >= offsets.size()) {
            throw new IllegalArgumentException("Index " + index + " is not stored in " + path());
        }
        long offset = offsets.get(slot);
        byte[] frame = new byte[RecordCodec.FRAME_BYTES];
        handle.readAt(offset, frame);
        int bodyLength = RecordCodec.declaredBodyLength(frame);
        if (bodyLength < 0) {
            throw CorruptionException.at(path(), offset, "implausible record length");
        }
        byte[] body = new byte[bodyLength];
        handle.readAt(offset + RecordCodec.FRAME_BYTES, body);
        if (!RecordCodec.checksumMatches(frame, body)) {
            throw CorruptionException.at(path(), offset, "record checksum mismatch");
        }
        return RecordCodec.decode(body, path(), offset);
    }

    void truncateFrom(long index) {
        int slot = (int) (index - firstIndex);
        if (slot >= offsets.size()) {
            return;
        }
        long offset = slot <= 0 ? HEADER_BYTES : offsets.get(slot);
        offsets.subList(Math.max(slot, 0), offsets.size()).clear();
        writePosition = offset;
        handle.truncate(offset);
    }

    void sync() {
        handle.sync();
    }

    @Override
    public void close() {
        handle.close();
    }

    private static byte[] header(long firstIndex) {
        ByteBuffer buffer = ByteBuffer.allocate(HEADER_BYTES);
        buffer.putLong(MAGIC);
        buffer.putInt(FORMAT_VERSION);
        buffer.putLong(firstIndex);
        byte[] bytes = buffer.array();
        ByteBuffer.wrap(bytes).putInt(20, checksum(bytes, 0, 20));
        return bytes;
    }

    private static int checksum(byte[] bytes, int offset, int length) {
        CRC32C crc = new CRC32C();
        crc.update(bytes, offset, length);
        return (int) crc.getValue();
    }
}
