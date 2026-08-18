/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.storage;

import dev.flotilla.storage.io.FileHandle;
import dev.flotilla.storage.io.FileIo;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Objects;

public final class StorageDirectory {

    public static final int LAYOUT_VERSION = 1;
    static final String LAYOUT_FILE = "LAYOUT";

    private final FileIo io;
    private final Path directory;

    private StorageDirectory(FileIo io, Path directory) {
        this.io = io;
        this.directory = directory;
    }

    public static StorageDirectory open(FileIo io, Path directory) {
        Objects.requireNonNull(io, "io");
        Objects.requireNonNull(directory, "directory");
        io.createDirectories(directory);

        StorageDirectory storage = new StorageDirectory(io, directory);
        Path layout = directory.resolve(LAYOUT_FILE);
        if (storage.hasLayout(layout)) {
            storage.verifyLayout(layout);
        } else {
            storage.writeLayout(layout);
        }
        return storage;
    }

    private boolean hasLayout(Path layout) {
        if (!io.exists(layout)) {
            return false;
        }
        try (FileHandle handle = io.open(layout)) {
            return handle.size() > 0;
        }
    }

    private void verifyLayout(Path layout) {
        try (FileHandle handle = io.open(layout)) {
            byte[] content = new byte[(int) Math.min(handle.size(), 64)];
            handle.readAt(0, content);
            String text = new String(content, StandardCharsets.UTF_8).trim();
            int version = parseVersion(text, layout);
            if (version != LAYOUT_VERSION) {
                throw new CorruptionException("Data directory " + directory + " uses storage layout version "
                        + version + " but this build understands version " + LAYOUT_VERSION
                        + ". Refusing to open it rather than risk misreading the log.");
            }
        }
    }

    private static int parseVersion(String text, Path layout) {
        String value = text.startsWith("version=") ? text.substring("version=".length()) : text;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new CorruptionException(layout + " does not contain a readable layout version: '" + text + "'");
        }
    }

    private void writeLayout(Path layout) {
        try (FileHandle handle = io.open(layout)) {
            handle.writeAt(0, ("version=" + LAYOUT_VERSION + "\n").getBytes(StandardCharsets.UTF_8));
            handle.sync();
        }
        io.syncDirectory(directory);
    }

    public Path path() {
        return directory;
    }

    public FileIo io() {
        return io;
    }
}
