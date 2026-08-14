/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.core;

import dev.flotilla.core.message.AppendEntriesRequest;
import dev.flotilla.core.message.RaftMessage;
import dev.flotilla.core.port.RandomSource;
import java.util.ArrayList;
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
    private final SortedMap<NodeId, List<LogEntry>> applied = new TreeMap<>();
    private final SortedSet<NodeId> isolated = new TreeSet<>();

    private final SortedMap<NodeId, Integer> appendRequests = new TreeMap<>();

    private TestCluster(SortedMap<NodeId, RaftNode> nodes, SortedMap<NodeId, InMemoryLogStore> logs) {
        this.nodes = nodes;
        this.logs = logs;
        nodes.keySet().forEach(id -> {
            applied.put(id, new ArrayList<>());
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
        ClusterConfig cluster = ClusterConfig.ofVoters(initialLogs.keySet());
        SortedMap<NodeId, RaftNode> nodes = new TreeMap<>();
        SortedMap<NodeId, InMemoryLogStore> logs = new TreeMap<>();

        long seed = 0;
        for (NodeId id : initialLogs.keySet()) {
            seed += 7919;
            InMemoryLogStore log = new InMemoryLogStore();
            List<LogEntry> entries = initialLogs.get(id);
            if (entries != null && !entries.isEmpty()) {
                log.append(entries);
            }
            RaftConfig config = tuning.apply(RaftConfig.builder(id)).build();
            logs.put(id, log);
            nodes.put(
                    id,
                    new RaftNode(config, cluster, log, RandomSource.seeded(seed), new HardState(initialTerm, null, 0)));
        }
        return new TestCluster(nodes, logs);
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

    List<LogEntry> appliedOf(NodeId id) {
        List<LogEntry> entries = applied.get(id);
        if (entries == null) {
            throw new IllegalArgumentException("Unknown node " + id + " in " + applied.keySet());
        }
        return entries;
    }

    List<Long> logTerms(NodeId id) {
        InMemoryLogStore log = log(id);
        return LongStream.rangeClosed(1, log.lastIndex())
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
