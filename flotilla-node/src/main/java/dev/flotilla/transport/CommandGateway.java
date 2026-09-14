/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.transport;

import dev.flotilla.core.Bytes;
import java.util.concurrent.CompletableFuture;

@FunctionalInterface
public interface CommandGateway {

    CompletableFuture<Executed> execute(Bytes command);
}
