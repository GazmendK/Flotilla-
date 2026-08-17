/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.storage.io;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

public final class RealFileIo implements FileIo {

    @Override
    public void createDirectories(Path directory) {
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create directory " + directory, e);
        }
    }

    @Override
    public boolean exists(Path path) {
        return Files.exists(path);
    }

    @Override
    public List<Path> listSorted(Path directory, String suffix) {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.filter(path -> path.getFileName().toString().endsWith(suffix))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot list " + directory, e);
        }
    }

    @Override
    public FileHandle open(Path path) {
        try {
            FileChannel channel = FileChannel.open(
                    path, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
            return new ChannelHandle(path, channel);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot open " + path, e);
        }
    }

    @Override
    public void delete(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot delete " + path, e);
        }
    }

    @Override
    public void syncDirectory(Path directory) {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException e) {
            DirectorySyncSupport.markUnsupported(directory, e);
        }
    }

    private static final class ChannelHandle implements FileHandle {

        private final Path path;
        private final FileChannel channel;

        private ChannelHandle(Path path, FileChannel channel) {
            this.path = path;
            this.channel = channel;
        }

        @Override
        public Path path() {
            return path;
        }

        @Override
        public long size() {
            try {
                return channel.size();
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot size " + path, e);
            }
        }

        @Override
        public void writeAt(long position, byte[] data, int offset, int length) {
            ByteBuffer buffer = ByteBuffer.wrap(data, offset, length);
            long at = position;
            try {
                while (buffer.hasRemaining()) {
                    int written = channel.write(buffer, at);
                    if (written <= 0) {
                        throw new IOException("Short write at " + at);
                    }
                    at += written;
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot write to " + path, e);
            }
        }

        @Override
        public int readAt(long position, byte[] into, int offset, int length) {
            ByteBuffer buffer = ByteBuffer.wrap(into, offset, length);
            long at = position;
            int total = 0;
            try {
                while (buffer.hasRemaining()) {
                    int read = channel.read(buffer, at);
                    if (read < 0) {
                        break;
                    }
                    at += read;
                    total += read;
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot read from " + path, e);
            }
            return total;
        }

        @Override
        public void truncate(long size) {
            try {
                if (size < channel.size()) {
                    channel.truncate(size);
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot truncate " + path, e);
            }
        }

        @Override
        public void sync() {
            try {
                channel.force(false);
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot sync " + path, e);
            }
        }

        @Override
        public void close() {
            try {
                channel.close();
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot close " + path, e);
            }
        }
    }
}
