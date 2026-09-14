/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.transport.grpc;

public class WireFormatException extends RuntimeException {

    public WireFormatException(String message) {
        super(message);
    }

    public WireFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
