/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.core;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.SortedSet;
import java.util.TreeSet;

@RaftSpec(value = "§4.1 Cluster membership changes", source = RaftSpec.Source.DISSERTATION)
public record ClusterConfig(SortedSet<NodeId> voters, SortedSet<NodeId> learners) {
    public ClusterConfig {
        Objects.requireNonNull(voters, "voters");
        Objects.requireNonNull(learners, "learners");
        if (voters.isEmpty()) {
            throw new IllegalArgumentException("A cluster needs at least one voter.");
        }
        SortedSet<NodeId> overlap = new TreeSet<>(voters);
        overlap.retainAll(learners);
        if (!overlap.isEmpty()) {
            throw new IllegalArgumentException("A node cannot be a voter and a learner at the same time: " + overlap);
        }
        voters = Collections.unmodifiableSortedSet(new TreeSet<>(voters));
        learners = Collections.unmodifiableSortedSet(new TreeSet<>(learners));
    }

    public static ClusterConfig ofVoters(NodeId... voters) {
        return ofVoters(List.of(voters));
    }

    public static ClusterConfig ofVoters(Collection<NodeId> voters) {
        return new ClusterConfig(new TreeSet<>(voters), new TreeSet<>());
    }

    public int quorum() {
        return voters.size() / 2 + 1;
    }

    public int faultTolerance() {
        return (voters.size() - 1) / 2;
    }

    public boolean isVoter(NodeId node) {
        return voters.contains(node);
    }

    public boolean isLearner(NodeId node) {
        return learners.contains(node);
    }

    public boolean contains(NodeId node) {
        return isVoter(node) || isLearner(node);
    }

    public SortedSet<NodeId> allMembers() {
        SortedSet<NodeId> all = new TreeSet<>(voters);
        all.addAll(learners);
        return Collections.unmodifiableSortedSet(all);
    }

    public ClusterConfig withLearner(NodeId node) {
        Objects.requireNonNull(node, "node");
        if (contains(node)) {
            throw new IllegalArgumentException(node + " is already a member of the cluster.");
        }
        SortedSet<NodeId> updated = new TreeSet<>(learners);
        updated.add(node);
        return new ClusterConfig(voters, updated);
    }

    public ClusterConfig withPromotion(NodeId node) {
        Objects.requireNonNull(node, "node");
        if (!isLearner(node)) {
            throw new IllegalArgumentException(node + " is not a learner and cannot be promoted.");
        }
        SortedSet<NodeId> newVoters = new TreeSet<>(voters);
        newVoters.add(node);
        SortedSet<NodeId> newLearners = new TreeSet<>(learners);
        newLearners.remove(node);
        return new ClusterConfig(newVoters, newLearners);
    }

    public ClusterConfig without(NodeId node) {
        Objects.requireNonNull(node, "node");
        if (!contains(node)) {
            throw new IllegalArgumentException(node + " is not a member of the cluster.");
        }
        SortedSet<NodeId> newVoters = new TreeSet<>(voters);
        newVoters.remove(node);
        SortedSet<NodeId> newLearners = new TreeSet<>(learners);
        newLearners.remove(node);
        return new ClusterConfig(newVoters, newLearners);
    }
}
