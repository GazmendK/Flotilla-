/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.flotilla.storage.io.RealFileIo;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StorageDirectoryTest {

    @TempDir
    Path root;

    @Test
    void createsTheDirectoryAndStampsTheLayoutVersion() throws IOException {
        Path directory = root.resolve("data");

        StorageDirectory storage = StorageDirectory.open(new RealFileIo(), directory);

        assertThat(storage.path()).isEqualTo(directory);
        assertThat(Files.readString(directory.resolve(StorageDirectory.LAYOUT_FILE)))
                .contains("version=" + StorageDirectory.LAYOUT_VERSION);
    }

    @Test
    void reopeningAnExistingDirectoryIsFine() {
        StorageDirectory.open(new RealFileIo(), root);

        assertThatCode(() -> StorageDirectory.open(new RealFileIo(), root)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a directory written by a future version is refused rather than misread")
    void refusesAnUnknownLayoutVersion() throws IOException {
        StorageDirectory.open(new RealFileIo(), root);
        Files.writeString(root.resolve(StorageDirectory.LAYOUT_FILE), "version=99\n", StandardCharsets.UTF_8);

        assertThatThrownBy(() -> StorageDirectory.open(new RealFileIo(), root))
                .isInstanceOf(CorruptionException.class)
                .hasMessageContaining("layout version 99")
                .hasMessageContaining("Refusing to open it");
    }

    @Test
    void refusesAnUnreadableLayoutFile() throws IOException {
        StorageDirectory.open(new RealFileIo(), root);
        Files.writeString(root.resolve(StorageDirectory.LAYOUT_FILE), "nonsense", StandardCharsets.UTF_8);

        assertThatThrownBy(() -> StorageDirectory.open(new RealFileIo(), root))
                .isInstanceOf(CorruptionException.class)
                .hasMessageContaining("readable layout version");
    }
}
