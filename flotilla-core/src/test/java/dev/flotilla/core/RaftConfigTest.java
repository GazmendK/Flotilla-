/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RaftConfigTest {
    private static final NodeId NODE = NodeId.of("n1");

    @Test
    void defaultsAreValidAndSensible() {
        RaftConfig config = RaftConfig.defaults(NODE);

        assertThat(config.preVote()).isTrue();
        assertThat(config.checkQuorum()).isTrue();
        assertThat(config.electionTimeoutSpreadTicks()).isPositive();
        assertThat(config.electionTimeoutMinTicks())
                .isGreaterThanOrEqualTo(config.heartbeatTicks() * RaftConfig.MIN_ELECTION_TO_HEARTBEAT_RATIO);
    }

    @Test
    @DisplayName("an election timeout without a range cannot resolve a split vote")
    void rejectsAnEmptyRandomizationWindow() {
        assertThatThrownBy(() -> RaftConfig.builder(NODE)
                        .heartbeatTicks(1)
                        .electionTimeoutTicks(10, 10)
                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nothing to randomize")
                .hasMessageContaining("splitting the vote");
    }

    @Test
    void rejectsAnInvertedRange() {
        assertThatThrownBy(() ->
                        RaftConfig.builder(NODE).electionTimeoutTicks(20, 10).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("strictly greater");
    }

    @Test
    @DisplayName("an election timeout too close to the heartbeat interval is refused, with a suggestion")
    void rejectsTimeoutsTooCloseToTheHeartbeat() {
        assertThatThrownBy(() -> RaftConfig.builder(NODE)
                        .heartbeatTicks(10)
                        .electionTimeoutTicks(12, 20)
                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("heartbeatTicks")
                .hasMessageContaining("electionTimeoutMinTicks=100");
    }

    @Test
    void rejectsAZeroHeartbeat() {
        assertThatThrownBy(() -> RaftConfig.builder(NODE).heartbeatTicks(0).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("heartbeatTicks must be at least 1");
    }

    @Test
    void rejectsBatchLimitsThatWouldStallReplication() {
        assertThatThrownBy(() -> RaftConfig.builder(NODE).maxEntriesPerAppend(0).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RaftConfig.builder(NODE).maxAppendBytes(0).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RaftConfig.builder(NODE).maxInflightAppends(0).build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("PreVote can be turned off, because a test needs to show what it prevents")
    void preVoteCanBeDisabledExplicitly() {
        assertThat(RaftConfig.builder(NODE).preVote(false).build().preVote()).isFalse();
    }
}
