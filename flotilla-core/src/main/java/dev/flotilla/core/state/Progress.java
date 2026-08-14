/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.core.state;

import dev.flotilla.core.RaftSpec;

@RaftSpec("Figure 2, Volatile state on leaders")
public final class Progress {

    private ProgressState state = ProgressState.PROBE;
    private long nextIndex;
    private long matchIndex;
    private int inflightCount;
    private boolean probeInFlight;

    public Progress(long nextIndex) {
        if (nextIndex < 1) {
            throw new IllegalArgumentException("nextIndex must be at least 1, was " + nextIndex);
        }
        this.nextIndex = nextIndex;
    }

    public long nextIndex() {
        return nextIndex;
    }

    public long matchIndex() {
        return matchIndex;
    }

    public ProgressState state() {
        return state;
    }

    public boolean maybeUpdate(long matchedIndex) {
        boolean advanced = matchedIndex > matchIndex;
        if (advanced) {
            matchIndex = matchedIndex;
        }
        if (nextIndex <= matchedIndex) {
            nextIndex = matchedIndex + 1;
        }
        return advanced;
    }

    public void resetNextIndex(long candidate) {
        nextIndex = Math.max(1, Math.max(matchIndex + 1, candidate));
    }

    public void becomeProbe() {
        state = ProgressState.PROBE;
        probeInFlight = false;
        inflightCount = 0;
    }

    public void becomeReplicate() {
        state = ProgressState.REPLICATE;
        nextIndex = matchIndex + 1;
        probeInFlight = false;
        inflightCount = 0;
    }

    public boolean isThrottled(int maxInflightAppends) {
        return switch (state) {
            case PROBE -> probeInFlight;
            case REPLICATE -> inflightCount >= maxInflightAppends;
            case SNAPSHOT -> true;
        };
    }

    public void recordSend() {
        if (state == ProgressState.PROBE) {
            probeInFlight = true;
        } else {
            inflightCount++;
        }
    }

    public void recordReply() {
        probeInFlight = false;
        if (inflightCount > 0) {
            inflightCount--;
        }
    }

    @Override
    public String toString() {
        return "Progress[" + state + " next=" + nextIndex + " match=" + matchIndex + "]";
    }
}
