/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.core.state;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CatchUpTrackerTest {

    private static final long ELECTION_TIMEOUT = 10;

    @Test
    @DisplayName("a round ends when the learner has what the leader had when the round began")
    void aRoundEndsAtItsTarget() {
        CatchUpTracker tracker = new CatchUpTracker(100, 0);

        tracker.observe(60, 100, 3);
        assertThat(tracker.completedRounds()).isZero();

        tracker.observe(100, 130, 7);
        assertThat(tracker.completedRounds()).isEqualTo(1);
        assertThat(tracker.lastRoundTicks()).isEqualTo(7);
        assertThat(tracker.isCaughtUp(ELECTION_TIMEOUT, 7)).isTrue();
    }

    @Test
    @DisplayName(
            "a learner that receives half as fast as the leader appends has ever longer rounds, however much it gets")
    void aTrailingLearnerNeverCatchesUp() {
        CatchUpTracker tracker = new CatchUpTracker(100, 0);
        tracker.observe(100, 120, 2);

        long leaderLastIndex = 120;
        long learnerMatch = 100;
        for (long tick = 3; tick <= 30; tick++) {
            leaderLastIndex += 10;
            learnerMatch += 5;
            tracker.observe(learnerMatch, leaderLastIndex, tick);
        }

        assertThat(learnerMatch)
                .as("it has long passed the index the first round aimed at")
                .isGreaterThan(120);
        assertThat(tracker.lastRoundTicks())
                .as("each round aims at the leader's log as it was when the round began, which keeps moving away")
                .isGreaterThan(ELECTION_TIMEOUT);
        assertThat(tracker.isCaughtUp(ELECTION_TIMEOUT, 30)).isFalse();
    }

    @Test
    @DisplayName("nothing is caught up before the first round has completed")
    void noRoundNoPromotion() {
        CatchUpTracker tracker = new CatchUpTracker(5, 0);

        assertThat(tracker.isCaughtUp(ELECTION_TIMEOUT, 1)).isFalse();
        assertThat(tracker.lastRoundTicks()).isEqualTo(Long.MAX_VALUE);
    }
}
