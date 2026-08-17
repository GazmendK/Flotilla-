/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.storage.io;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

public final class DirectorySyncSupport {

    private static final AtomicBoolean UNSUPPORTED = new AtomicBoolean();

    private DirectorySyncSupport() {}

    static void markUnsupported(Path directory, IOException cause) {
        if (UNSUPPORTED.compareAndSet(false, true)) {
            System.getLogger(DirectorySyncSupport.class.getName())
                    .log(
                            System.Logger.Level.INFO,
                            "Directory sync is not supported on this platform ({0}); relying on the file"
                                    + " system's own metadata ordering. Directory: {1}",
                            cause.getClass().getSimpleName(),
                            directory);
        }
    }

    public static boolean isUnsupported() {
        return UNSUPPORTED.get();
    }
}
