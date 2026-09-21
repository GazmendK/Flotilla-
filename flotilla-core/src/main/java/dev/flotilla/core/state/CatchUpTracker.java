/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.core.state;

import dev.flotilla.core.RaftSpec;

@RaftSpec(value = "§4.2.1 Catching up new servers", source = RaftSpec.Source.DISSERTATION)
public final class CatchUpTracker {

    private long roundTarget;
    private long roundStartedAtTick;
    private int completedRounds;
    private long lastRoundTicks = Long.MAX_VALUE;

    public CatchUpTracker(long target, long nowTick) {
        this.roundTarget = target;
        this.roundStartedAtTick = nowTick;
    }

    public void observe(long matchIndex, long lastLogIndex, long nowTick) {
        if (matchIndex < roundTarget) {
            return;
        }
        lastRoundTicks = nowTick - roundStartedAtTick;
        completedRounds++;
        roundTarget = lastLogIndex;
        roundStartedAtTick = nowTick;
    }

    public boolean isCaughtUp(long withinTicks, long nowTick) {
        return completedRounds > 0 && lastRoundTicks <= withinTicks && nowTick - roundStartedAtTick <= withinTicks;
    }

    public int completedRounds() {
        return completedRounds;
    }

    public long lastRoundTicks() {
        return lastRoundTicks;
    }

    public long currentRoundTicks(long nowTick) {
        return nowTick - roundStartedAtTick;
    }
}
