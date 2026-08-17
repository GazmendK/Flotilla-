/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.sim;

import dev.flotilla.core.message.RaftMessage;
import java.util.Comparator;
import java.util.Objects;

public record ScheduledMessage(long arrivalTime, long sequence, RaftMessage message) {

    public static final Comparator<ScheduledMessage> ORDER =
            Comparator.comparingLong(ScheduledMessage::arrivalTime).thenComparingLong(ScheduledMessage::sequence);

    public ScheduledMessage {
        Objects.requireNonNull(message, "message");
    }
}
