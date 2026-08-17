/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.storage;

import dev.flotilla.core.port.LogStore;
import dev.flotilla.storage.io.RealFileIo;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;

class SegmentedLogStoreContractTest extends LogStoreContractTest {

    @TempDir
    Path directory;

    @Override
    protected LogStore createStore() {
        StorageConfig config =
                StorageConfig.of(directory).withFsyncPolicy(FsyncPolicy.NEVER).withMaxSegmentBytes(8 * 1024);
        return SegmentedLogStore.open(StorageDirectory.open(new RealFileIo(), directory), config);
    }
}
