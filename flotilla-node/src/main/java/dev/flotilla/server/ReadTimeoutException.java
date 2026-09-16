/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.server;

public final class ReadTimeoutException extends RuntimeException {

    public ReadTimeoutException(String message) {
        super(message);
    }
}
