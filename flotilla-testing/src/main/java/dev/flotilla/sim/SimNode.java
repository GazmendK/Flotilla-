/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.sim;

import dev.flotilla.core.ClusterConfig;
import dev.flotilla.core.LogEntry;
import dev.flotilla.core.NodeId;
import dev.flotilla.core.RaftConfig;
import dev.flotilla.core.RaftNode;
import dev.flotilla.core.port.RandomSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class SimNode {

    private final RaftConfig config;
    private final ClusterConfig cluster;
    private final SimLogStore log = new SimLogStore();
    private final SimStableStore stable = new SimStableStore();
    private final long baseSeed;
    private final List<LogEntry> appliedHistory = new ArrayList<>();

    private RaftNode raft;
    private boolean running = true;
    private int restarts;
    private List<LogEntry> appliedThisStep = List.of();

    public SimNode(RaftConfig config, ClusterConfig cluster, long baseSeed) {
        this.config = Objects.requireNonNull(config, "config");
        this.cluster = Objects.requireNonNull(cluster, "cluster");
        this.baseSeed = baseSeed;
        this.raft = newRaftInstance();
    }

    private RaftNode newRaftInstance() {
        return new RaftNode(
                config, cluster, log, RandomSource.seeded(baseSeed + restarts * 1_000_003L), stable.recovered());
    }

    public NodeId id() {
        return config.nodeId();
    }

    public RaftNode raft() {
        return raft;
    }

    public SimLogStore log() {
        return log;
    }

    public SimStableStore stable() {
        return stable;
    }

    public boolean isRunning() {
        return running;
    }

    public int restarts() {
        return restarts;
    }

    public void crash() {
        running = false;
        log.discardUnsynced();
        restarts++;
        raft = newRaftInstance();
        appliedThisStep = List.of();
    }

    public void restart() {
        running = true;
    }

    public void beginStep() {
        appliedThisStep = List.of();
    }

    public void recordApplied(List<LogEntry> entries) {
        if (entries.isEmpty()) {
            return;
        }
        List<LogEntry> merged = new ArrayList<>(appliedThisStep);
        merged.addAll(entries);
        appliedThisStep = List.copyOf(merged);
        appliedHistory.addAll(entries);
    }

    public List<LogEntry> appliedThisStep() {
        return appliedThisStep;
    }

    public List<LogEntry> appliedHistory() {
        return List.copyOf(appliedHistory);
    }

    @Override
    public String toString() {
        return id() + (running ? "" : "(down)");
    }
}
