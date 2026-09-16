/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.transport;

import dev.flotilla.core.Bytes;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

public interface ClientEndpoint extends AutoCloseable {

    CompletableFuture<Executed> execute(InetSocketAddress target, Bytes command, Duration deadline);

    CompletableFuture<Executed> query(
            InetSocketAddress target, Bytes query, ReadConsistency consistency, Duration deadline);

    @Override
    void close();
}
