/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.client;

import org.jspecify.annotations.Nullable;

public final class IndeterminateResultException extends FlotillaClientException {

    public IndeterminateResultException(String message, @Nullable Throwable cause) {
        super(message, cause);
    }
}
