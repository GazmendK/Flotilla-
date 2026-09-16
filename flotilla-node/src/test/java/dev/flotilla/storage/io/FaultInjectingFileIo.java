/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.storage.io;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

public final class FaultInjectingFileIo implements FileIo {

    private final SortedMap<Path, VirtualFile> files = new TreeMap<>();
    private final SortedMap<Path, VirtualFile> durableEntries = new TreeMap<>();
    private final SortedSet<Path> directories = new TreeSet<>();

    private long writeCount;
    private long crashAtWrite = -1;
    private long tearAtWrite = -1;
    private long failAtWrite = -1;
    private boolean eagerMetadata;

    public long writeCount() {
        return writeCount;
    }

    public void crashAtWrite(long ordinal) {
        crashAtWrite = ordinal;
    }

    public void tearAtWrite(long ordinal) {
        tearAtWrite = ordinal;
    }

    public void failAtWrite(long ordinal) {
        failAtWrite = ordinal;
    }

    public void clearFaults() {
        crashAtWrite = -1;
        tearAtWrite = -1;
        failAtWrite = -1;
    }

    public void eagerMetadata(boolean enabled) {
        eagerMetadata = enabled;
    }

    public void resetWriteCount() {
        writeCount = 0;
    }

    public void crash() {
        files.clear();
        for (var entry : durableEntries.entrySet()) {
            entry.getValue().crash();
            files.put(entry.getKey(), entry.getValue());
        }
    }

    public long durableSizeOf(Path path) {
        VirtualFile file = files.get(path);
        return file == null ? -1 : file.durableSize();
    }

    @Override
    public void createDirectories(Path directory) {
        directories.add(directory);
    }

    @Override
    public boolean exists(Path path) {
        return files.containsKey(path);
    }

    @Override
    public List<Path> listSorted(Path directory, String suffix) {
        List<Path> matching = new ArrayList<>();
        for (Path path : files.keySet()) {
            if (directory.equals(path.getParent())
                    && path.getFileName().toString().endsWith(suffix)) {
                matching.add(path);
            }
        }
        return List.copyOf(matching);
    }

    @Override
    public FileHandle open(Path path) {
        VirtualFile file = files.computeIfAbsent(path, ignored -> new VirtualFile());
        if (eagerMetadata) {
            durableEntries.putIfAbsent(path, file);
        }
        return new VirtualHandle(path, file);
    }

    @Override
    public void delete(Path path) {
        crashBefore("delete of " + path);
        files.remove(path);
        if (eagerMetadata) {
            durableEntries.remove(path);
        }
    }

    @Override
    public void move(Path source, Path target) {
        crashBefore("rename of " + source + " to " + target);
        VirtualFile file = files.remove(source);
        if (file == null) {
            throw new IllegalStateException("Cannot move " + source + "; it does not exist");
        }
        files.put(target, file);
        if (eagerMetadata) {
            durableEntries.remove(source);
            durableEntries.put(target, file);
        }
    }

    @Override
    public void syncDirectory(Path directory) {
        crashBefore("sync of directory " + directory);
        directories.add(directory);
        durableEntries.keySet().removeIf(path -> directory.equals(path.getParent()));
        for (var entry : files.entrySet()) {
            if (directory.equals(entry.getKey().getParent())) {
                durableEntries.put(entry.getKey(), entry.getValue());
            }
        }
    }

    private int beginWrite(int length) {
        writeCount++;
        if (writeCount == failAtWrite) {
            throw new OutOfSpace("Simulated ENOSPC at write " + writeCount);
        }
        if (writeCount == tearAtWrite) {
            return Math.max(1, length / 2);
        }
        return length;
    }

    private void crashBefore(String operation) {
        writeCount++;
        if (writeCount == failAtWrite) {
            throw new OutOfSpace("Simulated failure of " + operation);
        }
        if (writeCount == crashAtWrite || writeCount == tearAtWrite) {
            crash();
            throw new SimulatedCrash("Simulated crash before " + operation);
        }
    }

    private void endWrite() {
        if (writeCount == crashAtWrite || writeCount == tearAtWrite) {
            crash();
            throw new SimulatedCrash("Simulated crash at write " + writeCount);
        }
    }

    public static final class SimulatedCrash extends RuntimeException {
        SimulatedCrash(String message) {
            super(message);
        }
    }

    public static final class OutOfSpace extends RuntimeException {
        OutOfSpace(String message) {
            super(message);
        }
    }

    private static final class VirtualFile {

        private byte[] current = new byte[0];
        private byte[] durable = new byte[0];

        void write(long position, byte[] data, int offset, int length) {
            int end = Math.toIntExact(position) + length;
            if (end > current.length) {
                current = Arrays.copyOf(current, end);
            }
            System.arraycopy(data, offset, current, Math.toIntExact(position), length);
        }

        int read(long position, byte[] into, int offset, int length) {
            if (position >= current.length) {
                return 0;
            }
            int available = (int) Math.min(length, current.length - position);
            System.arraycopy(current, Math.toIntExact(position), into, offset, available);
            return available;
        }

        void truncate(long size) {
            if (size < current.length) {
                current = Arrays.copyOf(current, Math.toIntExact(size));
            }
        }

        void sync() {
            durable = current.clone();
        }

        void crash() {
            current = durable.clone();
        }

        long size() {
            return current.length;
        }

        long durableSize() {
            return durable.length;
        }
    }

    private final class VirtualHandle implements FileHandle {

        private final Path path;
        private final VirtualFile file;

        private VirtualHandle(Path path, VirtualFile file) {
            this.path = path;
            this.file = file;
        }

        @Override
        public Path path() {
            return path;
        }

        @Override
        public long size() {
            return file.size();
        }

        @Override
        public void writeAt(long position, byte[] data, int offset, int length) {
            int effective = beginWrite(length);
            file.write(position, data, offset, effective);
            endWrite();
        }

        @Override
        public int readAt(long position, byte[] into, int offset, int length) {
            return file.read(position, into, offset, length);
        }

        @Override
        public void truncate(long size) {
            beginWrite(0);
            file.truncate(size);
            endWrite();
        }

        @Override
        public void sync() {
            crashBefore("sync of " + path);
            file.sync();
        }

        @Override
        public void close() {}
    }
}
