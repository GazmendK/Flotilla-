/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.server;

import dev.flotilla.core.message.RaftMessage;

@FunctionalInterface
public interface MessageSink {

    void send(RaftMessage message);

    static MessageSink discarding() {
        return message -> {};
    }
}
