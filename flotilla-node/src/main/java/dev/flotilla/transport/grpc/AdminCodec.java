/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.transport.grpc;

import dev.flotilla.core.CatchUpStatus;
import dev.flotilla.core.ClusterConfig;
import dev.flotilla.core.ConfChange;
import dev.flotilla.core.NodeId;
import dev.flotilla.transport.ClusterStatus;
import dev.flotilla.wire.v1.CatchUp;
import dev.flotilla.wire.v1.ChangeMembershipRequest;
import dev.flotilla.wire.v1.Configuration;
import dev.flotilla.wire.v1.DescribeClusterResponse;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;

final class AdminCodec {

    private AdminCodec() {}

    static ChangeMembershipRequest encode(ConfChange change) {
        ChangeMembershipRequest.Builder request = ChangeMembershipRequest.newBuilder();
        return switch (change) {
            case ConfChange.AddLearner add ->
                request.setAddLearner(add.node().value()).build();
            case ConfChange.Promote promote ->
                request.setPromote(promote.node().value()).build();
            case ConfChange.Remove remove ->
                request.setRemove(remove.node().value()).build();
        };
    }

    static ConfChange decode(ChangeMembershipRequest request) {
        return switch (request.getChangeCase()) {
            case ADD_LEARNER -> new ConfChange.AddLearner(node(request.getAddLearner()));
            case PROMOTE -> new ConfChange.Promote(node(request.getPromote()));
            case REMOVE -> new ConfChange.Remove(node(request.getRemove()));
            case CHANGE_NOT_SET -> throw new WireFormatException("A membership change names no change");
        };
    }

    static Configuration encode(ClusterConfig config) {
        Configuration.Builder wire = Configuration.newBuilder();
        config.voters().forEach(voter -> wire.addVoters(voter.value()));
        config.learners().forEach(learner -> wire.addLearners(learner.value()));
        return wire.build();
    }

    static ClusterConfig decode(Configuration wire) {
        TreeSet<NodeId> voters = new TreeSet<>();
        wire.getVotersList().forEach(voter -> voters.add(node(voter)));
        TreeSet<NodeId> learners = new TreeSet<>();
        wire.getLearnersList().forEach(learner -> learners.add(node(learner)));
        return new ClusterConfig(voters, learners);
    }

    static DescribeClusterResponse encode(ClusterStatus status) {
        DescribeClusterResponse.Builder wire = DescribeClusterResponse.newBuilder()
                .setNode(status.node().value())
                .setLeader(status.knownLeader().map(NodeId::value).orElse(""))
                .setTerm(status.term())
                .setConfiguration(encode(status.configuration()))
                .setConfigurationIndex(status.configurationIndex())
                .setConfigurationCommitted(status.configurationCommitted())
                .setCommitIndex(status.commitIndex())
                .setAppliedIndex(status.appliedIndex());
        for (Map.Entry<NodeId, CatchUpStatus> entry : status.catchUp().entrySet()) {
            CatchUpStatus catchUp = entry.getValue();
            wire.addCatchUp(CatchUp.newBuilder()
                    .setNode(entry.getKey().value())
                    .setCompletedRounds(catchUp.completedRounds())
                    .setLastRoundTicks(catchUp.lastRoundTicks())
                    .setCurrentRoundTicks(catchUp.currentRoundTicks())
                    .setMatchIndex(catchUp.matchIndex())
                    .setCaughtUp(catchUp.caughtUp()));
        }
        return wire.build();
    }

    static ClusterStatus decode(DescribeClusterResponse wire) {
        SortedMap<NodeId, CatchUpStatus> catchUp = new TreeMap<>();
        for (CatchUp learner : wire.getCatchUpList()) {
            catchUp.put(
                    node(learner.getNode()),
                    new CatchUpStatus(
                            learner.getCompletedRounds(),
                            learner.getLastRoundTicks(),
                            learner.getCurrentRoundTicks(),
                            learner.getMatchIndex(),
                            learner.getCaughtUp()));
        }
        return new ClusterStatus(
                node(wire.getNode()),
                wire.getLeader().isEmpty() ? null : node(wire.getLeader()),
                wire.getTerm(),
                decode(wire.getConfiguration()),
                wire.getConfigurationIndex(),
                wire.getConfigurationCommitted(),
                wire.getCommitIndex(),
                wire.getAppliedIndex(),
                catchUp);
    }

    static NodeId node(String value) {
        try {
            return NodeId.of(value);
        } catch (IllegalArgumentException invalid) {
            throw new WireFormatException("Not a node id: '" + value + "'", invalid);
        }
    }
}
