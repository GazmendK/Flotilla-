/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.core;

import dev.flotilla.core.message.AppendEntriesRequest;
import dev.flotilla.core.message.RaftMessage;
import dev.flotilla.core.port.RandomSource;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.UnaryOperator;
import java.util.stream.IntStream;
import java.util.stream.LongStream;

final class TestCluster {

    private static final int MAX_DELIVERY_ROUNDS = 2000;

    private final SortedMap<NodeId, RaftNode> nodes;
    private final SortedMap<NodeId, InMemoryLogStore> logs;
    private final SortedMap<NodeId, InMemorySnapshotStore> snapshots;
    private final SortedMap<NodeId, List<Snapshot>> installedSnapshots = new TreeMap<>();
    private final SortedMap<NodeId, List<ReadState>> readStates = new TreeMap<>();
    private final SortedMap<NodeId, List<LogEntry>> applied = new TreeMap<>();
    private final SortedSet<NodeId> isolated = new TreeSet<>();

    private final SortedMap<NodeId, Integer> appendRequests = new TreeMap<>();

    private TestCluster(
            SortedMap<NodeId, RaftNode> nodes,
            SortedMap<NodeId, InMemoryLogStore> logs,
            SortedMap<NodeId, InMemorySnapshotStore> snapshots) {
        this.nodes = nodes;
        this.logs = logs;
        this.snapshots = snapshots;
        nodes.keySet().forEach(id -> {
            applied.put(id, new ArrayList<>());
            installedSnapshots.put(id, new ArrayList<>());
            readStates.put(id, new ArrayList<>());
            appendRequests.put(id, 0);
        });
    }

    static TestCluster of(int voters) {
        return of(voters, UnaryOperator.identity());
    }

    static TestCluster of(int voters, UnaryOperator<RaftConfig.Builder> tuning) {
        SortedMap<NodeId, List<LogEntry>> empty = new TreeMap<>();
        IntStream.rangeClosed(1, voters).forEach(index -> empty.put(NodeId.of("n" + index), List.of()));
        return withLogs(empty, 0, tuning);
    }

    static TestCluster withLogs(SortedMap<NodeId, List<LogEntry>> initialLogs, long initialTerm) {
        return withLogs(initialLogs, initialTerm, UnaryOperator.identity());
    }

    static TestCluster withLogs(
            SortedMap<NodeId, List<LogEntry>> initialLogs, long initialTerm, UnaryOperator<RaftConfig.Builder> tuning) {
        return create(initialLogs, ClusterConfig.ofVoters(initialLogs.keySet()), initialTerm, tuning);
    }

    static TestCluster withMembers(ClusterConfig initial, Collection<NodeId> nodes) {
        SortedMap<NodeId, List<LogEntry>> empty = new TreeMap<>();
        nodes.forEach(id -> empty.put(id, List.of()));
        return create(empty, initial, 0, UnaryOperator.identity());
    }

    private static TestCluster create(
            SortedMap<NodeId, List<LogEntry>> initialLogs,
            ClusterConfig cluster,
            long initialTerm,
            UnaryOperator<RaftConfig.Builder> tuning) {
        SortedMap<NodeId, RaftNode> nodes = new TreeMap<>();
        SortedMap<NodeId, InMemoryLogStore> logs = new TreeMap<>();
        SortedMap<NodeId, InMemorySnapshotStore> snapshots = new TreeMap<>();

        long seed = 0;
        for (NodeId id : initialLogs.keySet()) {
            seed += 7919;
            InMemoryLogStore log = new InMemoryLogStore();
            List<LogEntry> entries = initialLogs.get(id);
            if (entries != null && !entries.isEmpty()) {
                log.append(entries);
            }
            RaftConfig config = tuning.apply(RaftConfig.builder(id)).build();
            InMemorySnapshotStore snapshotStore = new InMemorySnapshotStore();
            logs.put(id, log);
            snapshots.put(id, snapshotStore);
            nodes.put(
                    id,
                    new RaftNode(
                            config,
                            cluster,
                            log,
                            snapshotStore,
                            RandomSource.seeded(seed),
                            new HardState(initialTerm, null, 0)));
        }
        return new TestCluster(nodes, logs, snapshots);
    }

    static List<LogEntry> logWithTerms(long... terms) {
        return LongStream.range(0, terms.length)
                .mapToObj(offset -> LogEntry.noOp(terms[(int) offset], offset + 1))
                .toList();
    }

    void tick() {
        for (RaftNode node : nodes.values()) {
            node.tick();
        }
        deliver();
    }

    void tick(int times) {
        for (int i = 0; i < times; i++) {
            tick();
        }
    }

    void campaign(NodeId id) {
        node(id).campaign();
        deliver();
    }

    boolean propose(NodeId id, String value) {
        boolean accepted = node(id).propose(Bytes.ofUtf8(value));
        deliver();
        return accepted;
    }

    void deliver() {
        for (int round = 0; round < MAX_DELIVERY_ROUNDS; round++) {
            List<RaftMessage> batch = new ArrayList<>();
            for (RaftNode node : nodes.values()) {
                Ready ready = node.ready();
                batch.addAll(ready.messagesToSend());
                appliedOf(node.id()).addAll(ready.committedEntriesToApply());
                ready.snapshot().ifPresent(installedSnapshotsOf(node.id())::add);
                readStatesOf(node.id()).addAll(ready.readStates());
                node.advance();
            }
            if (batch.isEmpty()) {
                return;
            }
            for (RaftMessage message : batch) {
                if (isBlocked(message)) {
                    continue;
                }
                if (message instanceof AppendEntriesRequest) {
                    appendRequests.merge(message.to(), 1, Integer::sum);
                }
                node(message.to()).step(message);
            }
        }
        throw new IllegalStateException("Cluster did not settle within " + MAX_DELIVERY_ROUNDS + " rounds");
    }

    private boolean isBlocked(RaftMessage message) {
        return isolated.contains(message.from()) != isolated.contains(message.to());
    }

    void isolate(NodeId node) {
        isolated.add(node);
    }

    void heal() {
        isolated.clear();
    }

    int appendRequestsDeliveredTo(NodeId id) {
        Integer count = appendRequests.get(id);
        return count == null ? 0 : count;
    }

    RaftNode node(NodeId id) {
        RaftNode node = nodes.get(id);
        if (node == null) {
            throw new IllegalArgumentException("Unknown node " + id + " in " + nodes.keySet());
        }
        return node;
    }

    InMemoryLogStore log(NodeId id) {
        InMemoryLogStore log = logs.get(id);
        if (log == null) {
            throw new IllegalArgumentException("Unknown node " + id + " in " + logs.keySet());
        }
        return log;
    }

    InMemorySnapshotStore snapshots(NodeId id) {
        InMemorySnapshotStore store = snapshots.get(id);
        if (store == null) {
            throw new IllegalArgumentException("Unknown node " + id + " in " + snapshots.keySet());
        }
        return store;
    }

    List<ReadState> readStatesOf(NodeId id) {
        List<ReadState> states = readStates.get(id);
        if (states == null) {
            throw new IllegalArgumentException("Unknown node " + id + " in " + readStates.keySet());
        }
        return states;
    }

    List<Snapshot> installedSnapshotsOf(NodeId id) {
        List<Snapshot> installed = installedSnapshots.get(id);
        if (installed == null) {
            throw new IllegalArgumentException("Unknown node " + id + " in " + installedSnapshots.keySet());
        }
        return installed;
    }

    Snapshot takeSnapshot(NodeId id, long throughIndex) {
        Snapshot snapshot = new Snapshot(
                throughIndex,
                log(id).termAt(throughIndex),
                node(id).configurationAt(throughIndex),
                Bytes.ofUtf8("state through " + throughIndex));
        snapshots(id).save(snapshot);
        node(id).compactLog(throughIndex);
        return snapshot;
    }

    List<LogEntry> appliedOf(NodeId id) {
        List<LogEntry> entries = applied.get(id);
        if (entries == null) {
            throw new IllegalArgumentException("Unknown node " + id + " in " + applied.keySet());
        }
        return entries;
    }

    List<Long> logTerms(NodeId id) {
        InMemoryLogStore log = log(id);
        return LongStream.rangeClosed(log.firstIndex(), log.lastIndex())
                .boxed()
                .map(log::termAt)
                .toList();
    }

    long term(NodeId id) {
        return node(id).currentTerm();
    }

    long commitIndex(NodeId id) {
        return node(id).commitIndex();
    }

    RaftRole role(NodeId id) {
        return node(id).role();
    }

    List<NodeId> leaders() {
        return nodes.values().stream()
                .filter(RaftNode::isLeader)
                .map(RaftNode::id)
                .toList();
    }

    NodeId singleLeader() {
        List<NodeId> found = leaders();
        if (found.size() != 1) {
            throw new AssertionError("Expected exactly one leader but found " + found);
        }
        return found.getFirst();
    }

    List<NodeId> followers() {
        return nodes.values().stream()
                .filter(node -> !node.isLeader())
                .map(RaftNode::id)
                .toList();
    }
}
