/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.storage.io;

import java.nio.file.Path;
import java.util.List;

public interface FileIo {

    void createDirectories(Path directory);

    boolean exists(Path path);

    List<Path> listSorted(Path directory, String suffix);

    FileHandle open(Path path);

    void delete(Path path);

    void syncDirectory(Path directory);
}
