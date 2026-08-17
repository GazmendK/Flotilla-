/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.storage;

import dev.flotilla.core.HardState;
import dev.flotilla.core.NodeId;
import dev.flotilla.core.port.StableStore;
import dev.flotilla.storage.io.FileHandle;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.zip.CRC32C;
import org.jspecify.annotations.Nullable;

public final class FileStableStore implements StableStore, AutoCloseable {

    public static final String FILE_NAME = "hardstate";

    static final int SLOT_BYTES = 128;
    static final int SLOT_COUNT = 2;

    private static final int MAX_VOTE_BYTES = 64;
    private static final int CHECKSUM_OFFSET = SLOT_BYTES - Integer.BYTES;

    private final FileHandle handle;
    private final FsyncPolicy fsyncPolicy;

    private long generation;

    @Nullable
    private HardState recovered;

    private FileStableStore(FileHandle handle, FsyncPolicy fsyncPolicy) {
        this.handle = handle;
        this.fsyncPolicy = fsyncPolicy;
    }

    public static FileStableStore open(StorageDirectory storage, StorageConfig config) {
        Objects.requireNonNull(storage, "storage");
        Objects.requireNonNull(config, "config");
        Path path = storage.path().resolve(FILE_NAME);
        boolean fresh = !storage.io().exists(path);
        FileHandle handle = storage.io().open(path);
        FileStableStore store = new FileStableStore(handle, config.fsyncPolicy());
        store.recover();
        if (fresh) {
            storage.io().syncDirectory(storage.path());
        }
        return store;
    }

    private void recover() {
        byte[] file = new byte[SLOT_BYTES * SLOT_COUNT];
        handle.readAt(0, file);
        for (int slot = 0; slot < SLOT_COUNT; slot++) {
            Slot parsed = readSlot(file, slot * SLOT_BYTES);
            if (parsed != null && parsed.generation() > generation) {
                generation = parsed.generation();
                recovered = parsed.state();
            }
        }
    }

    @Override
    public Optional<HardState> load() {
        return Optional.ofNullable(recovered);
    }

    @Override
    public void persist(HardState state) {
        Objects.requireNonNull(state, "state");
        generation++;
        int slot = Math.floorMod(generation, SLOT_COUNT);
        handle.writeAt((long) slot * SLOT_BYTES, encode(generation, state));
        if (fsyncPolicy != FsyncPolicy.NEVER) {
            handle.sync();
        }
        recovered = state;
    }

    public long generation() {
        return generation;
    }

    public int activeSlot() {
        return Math.floorMod(generation, SLOT_COUNT);
    }

    @Override
    public void close() {
        handle.close();
    }

    private static byte[] encode(long generation, HardState state) {
        byte[] slot = new byte[SLOT_BYTES];
        ByteBuffer buffer = ByteBuffer.wrap(slot);
        buffer.putLong(generation);
        buffer.putLong(state.currentTerm());
        buffer.putLong(state.commitIndex());

        NodeId vote = state.votedFor();
        if (vote == null) {
            buffer.put((byte) 0);
        } else {
            byte[] encoded = vote.value().getBytes(StandardCharsets.UTF_8);
            if (encoded.length > MAX_VOTE_BYTES) {
                throw new IllegalArgumentException("Node id " + vote + " does not fit in " + MAX_VOTE_BYTES + " bytes");
            }
            buffer.put((byte) encoded.length);
            buffer.put(encoded);
        }

        ByteBuffer.wrap(slot).putInt(CHECKSUM_OFFSET, checksum(slot, 0, CHECKSUM_OFFSET));
        return slot;
    }

    @Nullable
    private static Slot readSlot(byte[] file, int offset) {
        if (offset + SLOT_BYTES > file.length) {
            return null;
        }
        int stored = ByteBuffer.wrap(file).getInt(offset + CHECKSUM_OFFSET);
        if (stored != checksum(file, offset, CHECKSUM_OFFSET)) {
            return null;
        }

        ByteBuffer buffer = ByteBuffer.wrap(file, offset, SLOT_BYTES).slice();
        long generation = buffer.getLong();
        if (generation <= 0) {
            return null;
        }
        long term = buffer.getLong();
        long commitIndex = buffer.getLong();
        int voteLength = Byte.toUnsignedInt(buffer.get());
        NodeId vote = null;
        if (voteLength > 0) {
            if (voteLength > MAX_VOTE_BYTES) {
                return null;
            }
            byte[] encoded = new byte[voteLength];
            buffer.get(encoded);
            vote = NodeId.of(new String(encoded, StandardCharsets.UTF_8));
        }
        return new Slot(generation, new HardState(term, vote, commitIndex));
    }

    private static int checksum(byte[] bytes, int offset, int length) {
        CRC32C crc = new CRC32C();
        crc.update(bytes, offset, length);
        return (int) crc.getValue();
    }

    private record Slot(long generation, HardState state) {}
}
