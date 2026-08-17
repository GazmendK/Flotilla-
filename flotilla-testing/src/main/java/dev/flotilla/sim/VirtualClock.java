/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.sim;

public final class VirtualClock {

    public static final long UNITS_PER_TICK = 1000;

    private long now;

    public long now() {
        return now;
    }

    public long tickNumber() {
        return now / UNITS_PER_TICK;
    }

    public void advanceTo(long time) {
        if (time < now) {
            throw new IllegalArgumentException("Virtual time must not move backwards: " + now + " -> " + time);
        }
        now = time;
    }

    @Override
    public String toString() {
        return "t=" + now;
    }
}
