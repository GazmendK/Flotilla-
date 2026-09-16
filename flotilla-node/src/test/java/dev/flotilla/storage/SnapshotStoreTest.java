/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.flotilla.core.Bytes;
import dev.flotilla.core.ClusterConfig;
import dev.flotilla.core.NodeId;
import dev.flotilla.core.Snapshot;
import dev.flotilla.storage.io.FileHandle;
import dev.flotilla.storage.io.RealFileIo;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SnapshotStoreTest {

    private static final ClusterConfig CLUSTER =
            ClusterConfig.ofVoters(NodeId.of("n1"), NodeId.of("n2"), NodeId.of("n3"));

    @TempDir
    Path directory;

    private FileSnapshotStore open() {
        return open(FileSnapshotStore.DEFAULT_RETAINED);
    }

    private FileSnapshotStore open(int retained) {
        return FileSnapshotStore.open(StorageDirectory.open(new RealFileIo(), directory), retained);
    }

    private static Snapshot snapshot(long index, long term, int payloadBytes) {
        return new Snapshot(index, term, CLUSTER, Bytes.ofUtf8("x".repeat(payloadBytes)));
    }

    private List<Path> filesEndingIn(String suffix) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(file -> file.getFileName().toString().endsWith(suffix))
                    .toList();
        }
    }

    @Test
    @DisplayName("a snapshot survives a restart with its index, term, cluster and payload intact")
    void aSnapshotSurvivesARestart() {
        Snapshot written = snapshot(4711, 9, 5000);
        open().save(written);

        assertThat(open().latest()).contains(written);
    }

    @Test
    void anEmptyDirectoryHasNoSnapshot() {
        assertThat(open().latest()).isEmpty();
    }

    @Test
    @DisplayName("the newest snapshot wins, whatever order they were written in")
    void theNewestSnapshotWins() {
        FileSnapshotStore store = open(5);
        store.save(snapshot(10, 1, 16));
        store.save(snapshot(200, 2, 16));
        store.save(snapshot(3000, 3, 16));

        assertThat(open(5).latest()).map(Snapshot::lastIncludedIndex).contains(3000L);
    }

    @Test
    void anOlderSnapshotIsRefused() {
        FileSnapshotStore store = open();
        store.save(snapshot(100, 1, 16));

        assertThatThrownBy(() -> store.save(snapshot(99, 1, 16)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("with an older one");
    }

    @Test
    @DisplayName("only the configured number of snapshots is kept, because a disk is not infinite")
    void oldSnapshotsAreDeleted() throws IOException {
        FileSnapshotStore store = open(2);
        for (int index = 1; index <= 6; index++) {
            store.save(snapshot(index * 100, 1, 512));
        }

        assertThat(filesEndingIn(FileSnapshotStore.SUFFIX)).hasSize(2);
        assertThat(open(2).latest()).map(Snapshot::lastIncludedIndex).contains(600L);
    }

    @Test
    @DisplayName("a half written temporary file is thrown away on start, never mistaken for a snapshot")
    void orphanedTemporaryFilesAreDiscarded() throws IOException {
        open().save(snapshot(50, 1, 64));
        Path orphan = directory.resolve("snapshot-00000000000000000099.snap.tmp");
        Files.write(orphan, new byte[] {1, 2, 3});

        FileSnapshotStore reopened = open();

        assertThat(filesEndingIn(FileSnapshotStore.TEMP_SUFFIX)).isEmpty();
        assertThat(reopened.latest()).map(Snapshot::lastIncludedIndex).contains(50L);
    }

    @Test
    @DisplayName("a corrupt newest snapshot falls back to the previous one instead of losing everything")
    void aCorruptSnapshotFallsBackToTheOneBefore() throws IOException {
        FileSnapshotStore store = open(3);
        store.save(snapshot(100, 1, 256));
        store.save(snapshot(200, 2, 256));

        Path newest = directory.resolve("snapshot-00000000000000000200.snap");
        byte[] content = Files.readAllBytes(newest);
        content[content.length - 1] ^= (byte) 0xFF;
        Files.write(newest, content);

        assertThat(open(3).latest()).map(Snapshot::lastIncludedIndex).contains(100L);
    }

    @Test
    @DisplayName("a truncated snapshot is refused rather than decoded into whatever fits")
    void aTruncatedSnapshotIsRefused() throws IOException {
        open().save(snapshot(100, 1, 4096));
        Path only = directory.resolve("snapshot-00000000000000000100.snap");
        byte[] content = Files.readAllBytes(only);
        Files.write(only, java.util.Arrays.copyOf(content, content.length / 2));

        assertThat(open().latest()).isEmpty();
    }

    @Test
    @DisplayName("flipping any bits of any single byte of a snapshot file is detected, checked exhaustively")
    void everySingleByteMutationIsDetected() {
        byte[] original = SnapshotCodec.encode(snapshot(77, 3, 24));

        for (int position = 0; position < original.length; position++) {
            for (int mask = 1; mask <= 0xFF; mask++) {
                byte[] file = original.clone();
                file[position] ^= (byte) mask;
                byte[] header = java.util.Arrays.copyOf(file, SnapshotCodec.HEADER_BYTES);
                byte[] body = java.util.Arrays.copyOfRange(file, SnapshotCodec.HEADER_BYTES, file.length);

                assertThatThrownBy(() -> SnapshotCodec.decode(header, body, "mutated"))
                        .as("flipping mask 0x%02X at byte %d went unnoticed", mask, position)
                        .isInstanceOf(CorruptionException.class);
            }
        }
    }

    @Test
    @DisplayName("the cluster configuration travels with the snapshot, which Phase 12 depends on")
    void theClusterConfigurationSurvivesTheRoundTrip() {
        ClusterConfig mixed = new ClusterConfig(
                new java.util.TreeSet<>(List.of(NodeId.of("alpha"), NodeId.of("beta"))),
                new java.util.TreeSet<>(List.of(NodeId.of("gamma"))));
        Snapshot written = new Snapshot(9, 4, mixed, Bytes.ofUtf8("state"));

        open().save(written);

        assertThat(open().latest()).map(Snapshot::cluster).contains(mixed);
    }

    @Test
    @DisplayName("a snapshot is never visible under its final name until all of its bytes are on disk")
    void theFinalNameAppearsOnlyAfterTheContentIsDurable() throws IOException {
        FileSnapshotStore store = open();
        store.save(snapshot(1, 1, 64));

        for (Path file : filesEndingIn(FileSnapshotStore.SUFFIX)) {
            try (FileHandle handle = new RealFileIo().open(file)) {
                assertThat(handle.size()).isGreaterThan(SnapshotCodec.HEADER_BYTES);
            }
        }
        assertThat(filesEndingIn(FileSnapshotStore.TEMP_SUFFIX)).isEmpty();
    }
}
