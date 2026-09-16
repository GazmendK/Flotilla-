/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.core.state;

import dev.flotilla.core.Bytes;
import dev.flotilla.core.NodeId;
import dev.flotilla.core.RaftSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

@RaftSpec(value = "§6.4 Processing read-only queries more efficiently", source = RaftSpec.Source.DISSERTATION)
public final class ReadIndexQueue {

    public record PendingRead(Bytes requestId, @Nullable NodeId origin) {
        public PendingRead {
            Objects.requireNonNull(requestId, "requestId");
        }
    }

    public record ConfirmedRead(Bytes requestId, @Nullable NodeId origin, long readIndex) {}

    private final List<PendingRead> waiting = new ArrayList<>();
    private final List<PendingRead> inFlight = new ArrayList<>();
    private long inFlightRound;
    private long inFlightIndex;

    public void enqueue(PendingRead read) {
        waiting.add(Objects.requireNonNull(read, "read"));
    }

    public boolean canStartRound() {
        return inFlightRound == 0 && !waiting.isEmpty();
    }

    public void startRound(long round, long readIndex) {
        if (!canStartRound()) {
            throw new IllegalStateException("a read round is already in flight or nothing is waiting");
        }
        inFlight.addAll(waiting);
        waiting.clear();
        inFlightRound = round;
        inFlightIndex = readIndex;
    }

    public List<ConfirmedRead> confirmThrough(long quorumAckedRound) {
        if (inFlightRound == 0 || quorumAckedRound < inFlightRound) {
            return List.of();
        }
        List<ConfirmedRead> confirmed = new ArrayList<>(inFlight.size());
        for (PendingRead read : inFlight) {
            confirmed.add(new ConfirmedRead(read.requestId(), read.origin(), inFlightIndex));
        }
        inFlight.clear();
        inFlightRound = 0;
        inFlightIndex = 0;
        return confirmed;
    }

    public int waiting() {
        return waiting.size();
    }

    public int inFlight() {
        return inFlight.size();
    }
}
