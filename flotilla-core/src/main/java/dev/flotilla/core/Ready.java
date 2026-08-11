/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.core;

import dev.flotilla.core.message.RaftMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

public record Ready(
        @Nullable HardState hardStateToPersist,
        List<LogEntry> entriesToPersist,
        List<RaftMessage> messagesToSend,
        List<LogEntry> committedEntriesToApply,
        @Nullable SoftState softStateChange,
        List<ReadState> readStates) {
    public static final Ready EMPTY = new Ready(null, List.of(), List.of(), List.of(), null, List.of());

    public Ready {
        entriesToPersist = List.copyOf(Objects.requireNonNull(entriesToPersist, "entriesToPersist"));
        messagesToSend = List.copyOf(Objects.requireNonNull(messagesToSend, "messagesToSend"));
        committedEntriesToApply =
                List.copyOf(Objects.requireNonNull(committedEntriesToApply, "committedEntriesToApply"));
        readStates = List.copyOf(Objects.requireNonNull(readStates, "readStates"));
    }

    public boolean isEmpty() {
        return hardStateToPersist == null
                && softStateChange == null
                && entriesToPersist.isEmpty()
                && messagesToSend.isEmpty()
                && committedEntriesToApply.isEmpty()
                && readStates.isEmpty();
    }

    public boolean requiresSync() {
        return hardStateToPersist != null || !entriesToPersist.isEmpty();
    }

    public Optional<HardState> hardState() {
        return Optional.ofNullable(hardStateToPersist);
    }

    public Optional<SoftState> softState() {
        return Optional.ofNullable(softStateChange);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        @Nullable
        private HardState hardState;

        @Nullable
        private SoftState softState;

        private final List<LogEntry> entries = new ArrayList<>();
        private final List<RaftMessage> messages = new ArrayList<>();
        private final List<LogEntry> committed = new ArrayList<>();
        private final List<ReadState> reads = new ArrayList<>();

        private Builder() {}

        public Builder hardState(HardState state) {
            this.hardState = Objects.requireNonNull(state, "state");
            return this;
        }

        public Builder softState(SoftState state) {
            this.softState = Objects.requireNonNull(state, "state");
            return this;
        }

        public Builder persist(LogEntry entry) {
            entries.add(Objects.requireNonNull(entry, "entry"));
            return this;
        }

        public Builder persistAll(List<LogEntry> toPersist) {
            entries.addAll(Objects.requireNonNull(toPersist, "toPersist"));
            return this;
        }

        public Builder send(RaftMessage message) {
            messages.add(Objects.requireNonNull(message, "message"));
            return this;
        }

        public Builder apply(List<LogEntry> toApply) {
            committed.addAll(Objects.requireNonNull(toApply, "toApply"));
            return this;
        }

        public Builder readState(ReadState readState) {
            reads.add(Objects.requireNonNull(readState, "readState"));
            return this;
        }

        public Ready build() {
            return new Ready(hardState, entries, messages, committed, softState, reads);
        }
    }
}
