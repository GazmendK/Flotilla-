/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.storage;

import dev.flotilla.core.Snapshot;
import dev.flotilla.core.port.SnapshotStore;
import dev.flotilla.storage.io.FileHandle;
import dev.flotilla.storage.io.FileIo;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

public final class FileSnapshotStore implements SnapshotStore {

    public static final String SUFFIX = ".snap";
    public static final String TEMP_SUFFIX = ".snap.tmp";
    public static final int DEFAULT_RETAINED = 2;

    private static final System.Logger LOG = System.getLogger(FileSnapshotStore.class.getName());

    private final FileIo io;
    private final Path directory;
    private final int retained;

    @Nullable
    private Snapshot latest;

    private FileSnapshotStore(FileIo io, Path directory, int retained) {
        this.io = io;
        this.directory = directory;
        this.retained = retained;
    }

    public static FileSnapshotStore open(StorageDirectory storage) {
        return open(storage, DEFAULT_RETAINED);
    }

    public static FileSnapshotStore open(StorageDirectory storage, int retained) {
        Objects.requireNonNull(storage, "storage");
        if (retained < 1) {
            throw new IllegalArgumentException("retained must be at least 1, was " + retained
                    + "; keeping no snapshot would strand every follower that falls behind");
        }
        FileSnapshotStore store = new FileSnapshotStore(storage.io(), storage.path(), retained);
        store.discardUnfinishedWrites();
        store.latest = store.newestReadable();
        return store;
    }

    @Override
    public Optional<Snapshot> latest() {
        return Optional.ofNullable(latest);
    }

    public void save(Snapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (latest != null && snapshot.lastIncludedIndex() < latest.lastIncludedIndex()) {
            throw new IllegalArgumentException("Cannot replace the snapshot through index " + latest.lastIncludedIndex()
                    + " with an older one through index " + snapshot.lastIncludedIndex() + ".");
        }
        Path temp = pathFor(snapshot.lastIncludedIndex(), TEMP_SUFFIX);
        byte[] encoded = SnapshotCodec.encode(snapshot);
        try (FileHandle handle = io.open(temp)) {
            handle.truncate(0);
            handle.writeAt(0, encoded);
            handle.sync();
        }
        io.move(temp, pathFor(snapshot.lastIncludedIndex(), SUFFIX));
        io.syncDirectory(directory);
        latest = snapshot;
        prune();
    }

    public List<Path> files() {
        return io.listSorted(directory, SUFFIX);
    }

    private void discardUnfinishedWrites() {
        for (Path orphan : io.listSorted(directory, TEMP_SUFFIX)) {
            io.delete(orphan);
        }
        if (!io.listSorted(directory, TEMP_SUFFIX).isEmpty()) {
            io.syncDirectory(directory);
        }
    }

    @Nullable
    private Snapshot newestReadable() {
        List<Path> candidates = new ArrayList<>(files());
        for (int i = candidates.size() - 1; i >= 0; i--) {
            Path path = candidates.get(i);
            try {
                return read(path);
            } catch (CorruptionException corrupt) {
                LOG.log(
                        System.Logger.Level.WARNING,
                        () -> "Ignoring unreadable snapshot " + path + ": " + corrupt.getMessage());
            }
        }
        return null;
    }

    Snapshot read(Path path) {
        try (FileHandle handle = io.open(path)) {
            byte[] header = new byte[SnapshotCodec.HEADER_BYTES];
            if (handle.readAt(0, header) != header.length) {
                throw new CorruptionException(path + " is shorter than a snapshot header.");
            }
            int bodyLength = SnapshotCodec.declaredBodyLength(header);
            if (bodyLength < 0) {
                throw new CorruptionException(path + " does not start with a readable snapshot header.");
            }
            byte[] body = new byte[bodyLength];
            if (handle.readAt(SnapshotCodec.HEADER_BYTES, body) != bodyLength) {
                throw new CorruptionException(path + " ends before the snapshot it declares.");
            }
            return SnapshotCodec.decode(header, body, path.toString());
        }
    }

    private void prune() {
        List<Path> present = files();
        if (present.size() <= retained) {
            return;
        }
        for (Path obsolete : present.subList(0, present.size() - retained)) {
            io.delete(obsolete);
        }
        io.syncDirectory(directory);
    }

    private Path pathFor(long index, String suffix) {
        return directory.resolve("snapshot-%020d%s".formatted(index, suffix));
    }

    @Override
    public String toString() {
        return "FileSnapshotStore[" + directory + " " + (latest == null ? "empty" : latest.toString()) + "]";
    }
}
