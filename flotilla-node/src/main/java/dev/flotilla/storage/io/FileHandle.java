/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.storage.io;

import java.nio.file.Path;

public interface FileHandle extends AutoCloseable {

    Path path();

    long size();

    void writeAt(long position, byte[] data, int offset, int length);

    default void writeAt(long position, byte[] data) {
        writeAt(position, data, 0, data.length);
    }

    int readAt(long position, byte[] into, int offset, int length);

    default int readAt(long position, byte[] into) {
        return readAt(position, into, 0, into.length);
    }

    void truncate(long size);

    void sync();

    @Override
    void close();
}
