/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.storage;

import java.nio.file.Path;
import java.util.Objects;

public record StorageConfig(Path directory, long maxSegmentBytes, FsyncPolicy fsyncPolicy) {

    public static final long DEFAULT_MAX_SEGMENT_BYTES = 64L * 1024 * 1024;

    public StorageConfig {
        Objects.requireNonNull(directory, "directory");
        Objects.requireNonNull(fsyncPolicy, "fsyncPolicy");
        if (maxSegmentBytes < 1024) {
            throw new IllegalArgumentException("maxSegmentBytes must be at least 1024, was " + maxSegmentBytes
                    + "; a segment smaller than a single record cannot make progress");
        }
    }

    public static StorageConfig of(Path directory) {
        return new StorageConfig(directory, DEFAULT_MAX_SEGMENT_BYTES, FsyncPolicy.ALWAYS);
    }

    public StorageConfig withMaxSegmentBytes(long bytes) {
        return new StorageConfig(directory, bytes, fsyncPolicy);
    }

    public StorageConfig withFsyncPolicy(FsyncPolicy policy) {
        return new StorageConfig(directory, maxSegmentBytes, policy);
    }
}
