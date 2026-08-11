/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.core;

import java.util.Objects;

public record RaftConfig(
        NodeId nodeId,
        int electionTimeoutMinTicks,
        int electionTimeoutMaxTicks,
        int heartbeatTicks,
        boolean preVote,
        boolean checkQuorum,
        int maxEntriesPerAppend,
        long maxAppendBytes,
        int maxInflightAppends) {
    public static final int MIN_ELECTION_TO_HEARTBEAT_RATIO = 3;

    public RaftConfig {
        Objects.requireNonNull(nodeId, "nodeId");

        if (heartbeatTicks < 1) {
            throw new IllegalArgumentException("heartbeatTicks must be at least 1, was " + heartbeatTicks + ".");
        }
        if (electionTimeoutMinTicks < 1) {
            throw new IllegalArgumentException(
                    "electionTimeoutMinTicks must be at least 1, was " + electionTimeoutMinTicks + ".");
        }
        if (electionTimeoutMaxTicks <= electionTimeoutMinTicks) {
            throw new IllegalArgumentException("electionTimeoutMaxTicks (" + electionTimeoutMaxTicks
                    + ") must be strictly greater than electionTimeoutMinTicks ("
                    + electionTimeoutMinTicks + "). Without a range there is nothing to randomize, "
                    + "and candidates that time out together keep splitting the vote.");
        }
        if (electionTimeoutMinTicks < heartbeatTicks * MIN_ELECTION_TO_HEARTBEAT_RATIO) {
            throw new IllegalArgumentException("electionTimeoutMinTicks (" + electionTimeoutMinTicks
                    + ") must be at least " + MIN_ELECTION_TO_HEARTBEAT_RATIO
                    + "x heartbeatTicks (" + heartbeatTicks + "), so a single delayed heartbeat "
                    + "does not unseat a healthy leader. A ratio of 10 or more is recommended; "
                    + "try heartbeatTicks=" + heartbeatTicks + ", electionTimeoutMinTicks="
                    + (heartbeatTicks * 10) + ".");
        }
        if (maxEntriesPerAppend < 1) {
            throw new IllegalArgumentException(
                    "maxEntriesPerAppend must be at least 1, was " + maxEntriesPerAppend + ".");
        }
        if (maxAppendBytes < 1) {
            throw new IllegalArgumentException("maxAppendBytes must be at least 1, was " + maxAppendBytes + ".");
        }
        if (maxInflightAppends < 1) {
            throw new IllegalArgumentException("maxInflightAppends must be at least 1, was " + maxInflightAppends
                    + ". A value of 1 disables pipelining.");
        }
    }

    public static Builder builder(NodeId nodeId) {
        return new Builder(nodeId);
    }

    public static final class Builder {
        private final NodeId nodeId;
        private int electionTimeoutMinTicks = 10;
        private int electionTimeoutMaxTicks = 20;
        private int heartbeatTicks = 1;
        private boolean preVote = true;
        private boolean checkQuorum = true;
        private int maxEntriesPerAppend = 64;
        private long maxAppendBytes = 1024L * 1024L;
        private int maxInflightAppends = 16;

        private Builder(NodeId nodeId) {
            this.nodeId = Objects.requireNonNull(nodeId, "nodeId");
        }

        public Builder electionTimeoutTicks(int minTicks, int maxTicks) {
            this.electionTimeoutMinTicks = minTicks;
            this.electionTimeoutMaxTicks = maxTicks;
            return this;
        }

        public Builder heartbeatTicks(int ticks) {
            this.heartbeatTicks = ticks;
            return this;
        }

        public Builder preVote(boolean enabled) {
            this.preVote = enabled;
            return this;
        }

        public Builder checkQuorum(boolean enabled) {
            this.checkQuorum = enabled;
            return this;
        }

        public Builder maxEntriesPerAppend(int entries) {
            this.maxEntriesPerAppend = entries;
            return this;
        }

        public Builder maxAppendBytes(long bytes) {
            this.maxAppendBytes = bytes;
            return this;
        }

        public Builder maxInflightAppends(int messages) {
            this.maxInflightAppends = messages;
            return this;
        }

        public RaftConfig build() {
            return new RaftConfig(
                    nodeId,
                    electionTimeoutMinTicks,
                    electionTimeoutMaxTicks,
                    heartbeatTicks,
                    preVote,
                    checkQuorum,
                    maxEntriesPerAppend,
                    maxAppendBytes,
                    maxInflightAppends);
        }
    }

    public static RaftConfig defaults(NodeId nodeId) {
        return builder(nodeId).build();
    }

    public int electionTimeoutSpreadTicks() {
        return electionTimeoutMaxTicks - electionTimeoutMinTicks;
    }
}
