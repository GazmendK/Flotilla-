/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.core;

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

final class TestCluster {

    private static final int MAX_DELIVERY_ROUNDS = 500;

    private final SortedMap<NodeId, RaftNode> nodes;
    private final SortedSet<NodeId> isolated = new TreeSet<>();

    private TestCluster(SortedMap<NodeId, RaftNode> nodes) {
        this.nodes = nodes;
    }

    static TestCluster of(int voters) {
        return of(voters, UnaryOperator.identity());
    }

    static TestCluster of(int voters, UnaryOperator<RaftConfig.Builder> tuning) {
        List<NodeId> ids = IntStream.rangeClosed(1, voters)
                .mapToObj(index -> NodeId.of("n" + index))
                .toList();
        ClusterConfig cluster = ClusterConfig.ofVoters(ids);

        SortedMap<NodeId, RaftNode> nodes = new TreeMap<>();
        long seed = 0;
        for (NodeId id : ids) {
            seed += 7919;
            RaftConfig config = tuning.apply(RaftConfig.builder(id)).build();
            nodes.put(id, RaftNode.bootstrap(config, cluster, new InMemoryLogStore(), RandomSource.seeded(seed)));
        }
        return new TestCluster(nodes);
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

    void deliver() {
        for (int round = 0; round < MAX_DELIVERY_ROUNDS; round++) {
            List<RaftMessage> batch = new ArrayList<>();
            for (RaftNode node : nodes.values()) {
                batch.addAll(node.ready().messagesToSend());
                node.advance();
            }
            if (batch.isEmpty()) {
                return;
            }
            for (RaftMessage message : batch) {
                if (!isBlocked(message)) {
                    node(message.to()).step(message);
                }
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

    RaftNode node(NodeId id) {
        RaftNode node = nodes.get(id);
        if (node == null) {
            throw new IllegalArgumentException("Unknown node " + id + " in " + nodes.keySet());
        }
        return node;
    }

    long term(NodeId id) {
        return node(id).currentTerm();
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
