/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.core;

public record CatchUpStatus(
        int completedRounds, long lastRoundTicks, long currentRoundTicks, long matchIndex, boolean caughtUp) {}
