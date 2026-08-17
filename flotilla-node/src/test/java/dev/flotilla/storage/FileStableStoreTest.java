/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.storage;

import static org.assertj.core.api.Assertions.assertThat;

import dev.flotilla.core.HardState;
import dev.flotilla.core.NodeId;
import dev.flotilla.storage.io.RealFileIo;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileStableStoreTest {

    @TempDir
    Path directory;

    private FileStableStore open() {
        StorageConfig config = StorageConfig.of(directory).withFsyncPolicy(FsyncPolicy.NEVER);
        return FileStableStore.open(StorageDirectory.open(new RealFileIo(), directory), config);
    }

    @Test
    @DisplayName("a fresh store has nothing to recover")
    void aFreshStoreIsEmpty() {
        try (FileStableStore store = open()) {
            assertThat(store.load()).isEmpty();
        }
    }

    @Test
    void theVoteAndTermSurviveAReopen() {
        HardState state = new HardState(7, NodeId.of("n3"), 42);
        try (FileStableStore store = open()) {
            store.persist(state);
        }

        try (FileStableStore reopened = open()) {
            assertThat(reopened.load()).contains(state);
        }
    }

    @Test
    void anAbsentVoteSurvivesAReopen() {
        try (FileStableStore store = open()) {
            store.persist(new HardState(3, null, 0));
        }

        try (FileStableStore reopened = open()) {
            assertThat(reopened.load()).contains(new HardState(3, null, 0));
            assertThat(reopened.load().orElseThrow().hasVoted()).isFalse();
        }
    }

    @Test
    @DisplayName("writes alternate between the two slots, so a torn write can never destroy both")
    void writesAlternateBetweenSlots() {
        try (FileStableStore store = open()) {
            store.persist(new HardState(1, null, 0));
            int first = store.activeSlot();
            store.persist(new HardState(2, null, 0));
            int second = store.activeSlot();
            store.persist(new HardState(3, null, 0));

            assertThat(second).isNotEqualTo(first);
            assertThat(store.activeSlot()).isEqualTo(first);
        }
    }

    @Test
    @DisplayName("the newest state wins across reopens, and the generation keeps counting")
    void theNewestStateWins() {
        try (FileStableStore store = open()) {
            store.persist(new HardState(1, NodeId.of("n1"), 0));
            store.persist(new HardState(2, NodeId.of("n2"), 5));
            assertThat(store.generation()).isEqualTo(2);
        }

        try (FileStableStore reopened = open()) {
            assertThat(reopened.load()).contains(new HardState(2, NodeId.of("n2"), 5));
            assertThat(reopened.generation()).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("destroying the newest slot falls back to the previous one instead of losing the vote entirely")
    void aDamagedSlotFallsBackToTheOlderOne() throws IOException {
        HardState older = new HardState(4, NodeId.of("n1"), 1);
        HardState newer = new HardState(5, NodeId.of("n2"), 2);
        int damagedSlot;
        try (FileStableStore store = open()) {
            store.persist(older);
            store.persist(newer);
            damagedSlot = store.activeSlot();
        }

        Path file = directory.resolve(FileStableStore.FILE_NAME);
        byte[] content = Files.readAllBytes(file);
        content[damagedSlot * FileStableStore.SLOT_BYTES + 3] ^= 0x7F;
        Files.write(file, content);

        try (FileStableStore recovered = open()) {
            assertThat(recovered.load()).contains(older);
        }
    }

    @Test
    @DisplayName("a store whose file is entirely garbage reports nothing rather than a wrong vote")
    void completeGarbageReadsAsEmpty() throws IOException {
        try (FileStableStore store = open()) {
            store.persist(new HardState(9, NodeId.of("n1"), 3));
        }

        Path file = directory.resolve(FileStableStore.FILE_NAME);
        byte[] garbage = new byte[FileStableStore.SLOT_BYTES * FileStableStore.SLOT_COUNT];
        java.util.Arrays.fill(garbage, (byte) 0x5A);
        Files.write(file, garbage);

        try (FileStableStore recovered = open()) {
            assertThat(recovered.load()).isEmpty();
        }
    }
}
