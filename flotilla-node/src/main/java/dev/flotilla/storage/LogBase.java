/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.storage;

import dev.flotilla.storage.io.FileHandle;
import dev.flotilla.storage.io.FileIo;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.zip.CRC32C;

final class LogBase implements AutoCloseable {

    static final String FILE_NAME = "logbase";
    static final int SLOT_BYTES = 32;
    static final int SLOT_COUNT = 2;

    private static final int CHECKSUM_OFFSET = 3 * Long.BYTES;

    private final FileHandle handle;

    private long generation;
    private long index;
    private long term;

    private LogBase(FileHandle handle) {
        this.handle = handle;
    }

    static LogBase open(FileIo io, Path directory) {
        Path path = directory.resolve(FILE_NAME);
        boolean fresh = !io.exists(path);
        LogBase base = new LogBase(io.open(path));
        base.recover();
        if (fresh) {
            io.syncDirectory(directory);
        }
        return base;
    }

    long index() {
        return index;
    }

    long term() {
        return term;
    }

    void update(long newIndex, long newTerm) {
        generation++;
        int slot = Math.floorMod(generation, SLOT_COUNT);
        handle.writeAt((long) slot * SLOT_BYTES, encode(generation, newIndex, newTerm));
        handle.sync();
        index = newIndex;
        term = newTerm;
    }

    @Override
    public void close() {
        handle.close();
    }

    private void recover() {
        byte[] file = new byte[SLOT_BYTES * SLOT_COUNT];
        handle.readAt(0, file);
        for (int slot = 0; slot < SLOT_COUNT; slot++) {
            ByteBuffer buffer =
                    ByteBuffer.wrap(file, slot * SLOT_BYTES, SLOT_BYTES).slice();
            int stored = buffer.getInt(CHECKSUM_OFFSET);
            if (stored != checksum(file, slot * SLOT_BYTES)) {
                continue;
            }
            long slotGeneration = buffer.getLong(0);
            if (slotGeneration > generation) {
                generation = slotGeneration;
                index = buffer.getLong(Long.BYTES);
                term = buffer.getLong(2 * Long.BYTES);
            }
        }
    }

    private static byte[] encode(long generation, long index, long term) {
        byte[] slot = new byte[SLOT_BYTES];
        ByteBuffer buffer = ByteBuffer.wrap(slot);
        buffer.putLong(generation).putLong(index).putLong(term);
        buffer.putInt(CHECKSUM_OFFSET, checksum(slot, 0));
        return slot;
    }

    private static int checksum(byte[] bytes, int offset) {
        CRC32C crc = new CRC32C();
        crc.update(bytes, offset, CHECKSUM_OFFSET);
        return (int) crc.getValue();
    }
}
