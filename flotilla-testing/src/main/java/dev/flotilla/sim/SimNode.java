/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.sim;

import dev.flotilla.core.Bytes;
import dev.flotilla.core.ClusterConfig;
import dev.flotilla.core.InMemorySnapshotStore;
import dev.flotilla.core.LogEntry;
import dev.flotilla.core.NodeId;
import dev.flotilla.core.RaftConfig;
import dev.flotilla.core.RaftNode;
import dev.flotilla.core.ReadState;
import dev.flotilla.core.Snapshot;
import dev.flotilla.core.port.RandomSource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class SimNode {

    private final RaftConfig config;
    private final ClusterConfig cluster;
    private final SimLogStore log = new SimLogStore();
    private final SimStableStore stable = new SimStableStore();
    private final InMemorySnapshotStore snapshots = new InMemorySnapshotStore();
    private final SimStateMachine stateMachine = new SimStateMachine();
    private final long baseSeed;
    private final List<LogEntry> appliedHistory = new ArrayList<>();
    private final Map<Bytes, Long> confirmedReads = new HashMap<>();

    private RaftNode raft;
    private boolean running = true;
    private int restarts;
    private long restoredFromSnapshotAt;
    private List<LogEntry> appliedThisStep = List.of();

    public SimNode(RaftConfig config, ClusterConfig cluster, long baseSeed) {
        this.config = Objects.requireNonNull(config, "config");
        this.cluster = Objects.requireNonNull(cluster, "cluster");
        this.baseSeed = baseSeed;
        this.raft = newRaftInstance();
    }

    private RaftNode newRaftInstance() {
        return new RaftNode(
                config,
                cluster,
                log,
                snapshots,
                RandomSource.seeded(baseSeed + restarts * 1_000_003L),
                stable.recovered());
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

    public InMemorySnapshotStore snapshots() {
        return snapshots;
    }

    public SimStateMachine stateMachine() {
        return stateMachine;
    }

    public boolean isRunning() {
        return running;
    }

    public int restarts() {
        return restarts;
    }

    public long restoredFromSnapshotAt() {
        return restoredFromSnapshotAt;
    }

    public void crash() {
        running = false;
        log.discardUnsynced();
        restarts++;
        raft = newRaftInstance();
        appliedThisStep = List.of();
        stateMachine.recover(snapshots.latest(), log, stable.recovered().commitIndex());
        confirmedReads.clear();
    }

    public void restart() {
        running = true;
    }

    public void endStep() {
        appliedThisStep = List.of();
    }

    public void recordApplied(List<LogEntry> entries) {
        if (entries.isEmpty()) {
            return;
        }
        entries.forEach(stateMachine::apply);
        List<LogEntry> merged = new ArrayList<>(appliedThisStep);
        merged.addAll(entries);
        appliedThisStep = List.copyOf(merged);
        appliedHistory.addAll(entries);
    }

    public void restoreFrom(Snapshot snapshot) {
        stateMachine.restore(snapshot.lastIncludedIndex(), snapshot.data());
        snapshots.save(snapshot);
        restoredFromSnapshotAt = snapshot.lastIncludedIndex();
    }

    public Optional<Snapshot> takeSnapshot() {
        long through = stateMachine.lastApplied();
        if (through <= log.firstIndex() - 1 || through > log.lastIndex()) {
            return Optional.empty();
        }
        Snapshot snapshot =
                new Snapshot(through, log.termAt(through), raft.configurationAt(through), stateMachine.capture());
        snapshots.save(snapshot);
        raft.compactLog(through);
        return Optional.of(snapshot);
    }

    public void recordReadStates(List<ReadState> readStates) {
        for (ReadState readState : readStates) {
            confirmedReads.put(readState.requestId(), readState.readIndex());
        }
    }

    public Optional<Long> takeReadIndex(Bytes requestId) {
        return Optional.ofNullable(confirmedReads.remove(requestId));
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
