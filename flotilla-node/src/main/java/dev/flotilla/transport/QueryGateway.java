/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.transport;

import dev.flotilla.core.Bytes;
import java.util.concurrent.CompletableFuture;

@FunctionalInterface
public interface QueryGateway {

    CompletableFuture<Executed> query(Bytes query, ReadConsistency consistency);

    static QueryGateway unsupported() {
        return (query, consistency) -> CompletableFuture.failedFuture(
                CallFailure.of(CallFailure.Kind.INVALID, "this node answers no queries"));
    }
}
