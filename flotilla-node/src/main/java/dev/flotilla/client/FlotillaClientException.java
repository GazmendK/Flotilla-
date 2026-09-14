/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.client;

import org.jspecify.annotations.Nullable;

public class FlotillaClientException extends RuntimeException {

    public FlotillaClientException(String message, @Nullable Throwable cause) {
        super(message, cause);
    }
}
