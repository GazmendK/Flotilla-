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
    private long pendingSnapshotIndex;
    private int snapshotElapsedTicks;

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

    public long pendingSnapshotIndex() {
        return pendingSnapshotIndex;
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
        forgetSnapshot();
    }

    public void becomeReplicate() {
        state = ProgressState.REPLICATE;
        nextIndex = matchIndex + 1;
        probeInFlight = false;
        inflightCount = 0;
        forgetSnapshot();
    }

    @RaftSpec("§7 Log compaction")
    public void becomeSnapshot(long snapshotIndex) {
        if (snapshotIndex < matchIndex) {
            throw new IllegalArgumentException("A snapshot through index " + snapshotIndex
                    + " cannot catch up a peer that already matches through index " + matchIndex + ".");
        }
        state = ProgressState.SNAPSHOT;
        probeInFlight = false;
        inflightCount = 0;
        pendingSnapshotIndex = snapshotIndex;
        snapshotElapsedTicks = 0;
    }

    public void recordSnapshotTick() {
        if (state == ProgressState.SNAPSHOT) {
            snapshotElapsedTicks++;
        }
    }

    public boolean snapshotTimedOut(int timeoutTicks) {
        return state == ProgressState.SNAPSHOT && snapshotElapsedTicks >= timeoutTicks;
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

    private void forgetSnapshot() {
        pendingSnapshotIndex = 0;
        snapshotElapsedTicks = 0;
    }

    @Override
    public String toString() {
        return "Progress[" + state + " next=" + nextIndex + " match=" + matchIndex
                + (state == ProgressState.SNAPSHOT ? " snapshot=" + pendingSnapshotIndex : "") + "]";
    }
}
