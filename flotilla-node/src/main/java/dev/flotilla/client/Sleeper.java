/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.client;

import java.time.Duration;

@FunctionalInterface
public interface Sleeper {

    void sleep(Duration duration);

    static Sleeper system() {
        return duration -> {
            try {
                Thread.sleep(duration);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new FlotillaClientException("Interrupted while backing off", interrupted);
            }
        };
    }
}
