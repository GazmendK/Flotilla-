/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.sim;

import dev.flotilla.core.NodeId;
import java.util.Collection;
import java.util.Objects;
import java.util.SortedMap;
import java.util.StringJoiner;
import java.util.TreeMap;

public final class VirtualNetwork {

    private final SimConfig config;
    private final DeterministicRandom random;
    private final SortedMap<NodeId, Integer> sides = new TreeMap<>();

    public VirtualNetwork(SimConfig config, DeterministicRandom random, Collection<NodeId> nodes) {
        this.config = Objects.requireNonNull(config, "config");
        this.random = Objects.requireNonNull(random, "random");
        heal(nodes);
    }

    public boolean connected(NodeId from, NodeId to) {
        return Objects.equals(sides.get(from), sides.get(to));
    }

    public void heal(Collection<NodeId> nodes) {
        sides.clear();
        nodes.forEach(node -> sides.put(node, 0));
    }

    public void isolateTo(NodeId node) {
        sides.put(node, 1);
    }

    public void repartition(Collection<NodeId> nodes) {
        for (NodeId node : nodes) {
            sides.put(node, random.nextInt(2));
        }
    }

    public boolean isPartitioned() {
        return sides.values().stream().distinct().count() > 1;
    }

    public long latencyUnits() {
        if (random.chance(config.slowLinkProbability())) {
            return config.slowLinkLatencyUnits();
        }
        return random.between(config.minLatencyUnits(), config.maxLatencyUnits());
    }

    public boolean drops() {
        return random.chance(config.dropProbability());
    }

    public boolean duplicates() {
        return random.chance(config.duplicateProbability());
    }

    public String describe() {
        if (!isPartitioned()) {
            return "connected";
        }
        StringJoiner left = new StringJoiner(",");
        StringJoiner right = new StringJoiner(",");
        sides.forEach((node, side) -> {
            if (side == 0) {
                left.add(node.value());
            } else {
                right.add(node.value());
            }
        });
        return "[" + left + "] | [" + right + "]";
    }
}
