/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.core;

import dev.flotilla.core.message.AppendEntriesRequest;
import dev.flotilla.core.message.AppendEntriesResponse;
import dev.flotilla.core.message.InstallSnapshotRequest;
import dev.flotilla.core.message.InstallSnapshotResponse;
import dev.flotilla.core.message.RaftMessage;
import dev.flotilla.core.message.ReadIndexRequest;
import dev.flotilla.core.message.ReadIndexResponse;
import dev.flotilla.core.message.RequestVoteRequest;
import dev.flotilla.core.message.RequestVoteResponse;
import dev.flotilla.core.message.TimeoutNowRequest;
import dev.flotilla.core.port.LogStore;
import dev.flotilla.core.port.RandomSource;
import dev.flotilla.core.state.Candidate;
import dev.flotilla.core.state.Follower;
import dev.flotilla.core.state.Leader;
import dev.flotilla.core.state.RaftState;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

@RaftSpec("Figure 2, Rules for Servers")
public final class RaftNode {

    private final RaftConfig config;
    private final ClusterConfig cluster;
    private final LogStore log;
    private final ElectionTimer electionTimer;

    private RaftState state;
    private long currentTerm;

    @Nullable
    private NodeId votedFor;

    private long commitIndex;
    private int heartbeatElapsedTicks;

    private Ready.Builder pending = Ready.builder();
    private HardState publishedHardState;
    private SoftState publishedSoftState;

    public RaftNode(RaftConfig config, ClusterConfig cluster, LogStore log, RandomSource random, HardState persisted) {
        this.config = Objects.requireNonNull(config, "config");
        this.cluster = Objects.requireNonNull(cluster, "cluster");
        this.log = Objects.requireNonNull(log, "log");
        Objects.requireNonNull(persisted, "persisted");
        if (!cluster.contains(config.nodeId())) {
            throw new IllegalArgumentException(
                    config.nodeId() + " is not a member of the cluster " + cluster.allMembers());
        }
        this.electionTimer = new ElectionTimer(config, Objects.requireNonNull(random, "random"));
        this.currentTerm = persisted.currentTerm();
        this.votedFor = persisted.votedFor();
        this.commitIndex = persisted.commitIndex();
        this.state = Follower.withoutLeader();
        this.publishedHardState = persisted;
        this.publishedSoftState = SoftState.follower();
    }

    public static RaftNode bootstrap(RaftConfig config, ClusterConfig cluster, LogStore log, RandomSource random) {
        return new RaftNode(config, cluster, log, random, HardState.INITIAL);
    }

    public NodeId id() {
        return config.nodeId();
    }

    public long currentTerm() {
        return currentTerm;
    }

    public RaftRole role() {
        return state.role();
    }

    public Optional<NodeId> leader() {
        return Optional.ofNullable(leaderId());
    }

    public Optional<NodeId> votedFor() {
        return Optional.ofNullable(votedFor);
    }

    public long commitIndex() {
        return commitIndex;
    }

    public boolean isLeader() {
        return state instanceof Leader;
    }

    public void tick() {
        switch (state) {
            case Leader leader -> tickLeader(leader);
            case Follower ignored -> tickElectionTimeout();
            case Candidate ignored -> tickElectionTimeout();
        }
    }

    public void campaign() {
        startElection(config.preVote());
    }

    public Ready ready() {
        HardState hardState = currentHardState();
        if (!hardState.equals(publishedHardState)) {
            pending.hardState(hardState);
        }
        SoftState softState = currentSoftState();
        if (!softState.equals(publishedSoftState)) {
            pending.softState(softState);
        }
        return pending.build();
    }

    public void advance() {
        publishedHardState = currentHardState();
        publishedSoftState = currentSoftState();
        pending = Ready.builder();
    }

    public void step(RaftMessage message) {
        Objects.requireNonNull(message, "message");
        if (!message.to().equals(id())) {
            throw new IllegalArgumentException("Message addressed to " + message.to() + " was delivered to " + id());
        }

        if (message instanceof RequestVoteRequest vote && isWithinLeaderLease(vote.from())) {
            return;
        }

        if (message.term() > currentTerm) {
            if (!keepsTermOnHigherTerm(message)) {
                becomeFollower(message.term(), leaderOf(message));
            }
        } else if (message.term() < currentTerm) {
            replyToStaleSender(message);
            return;
        }

        switch (message) {
            case RequestVoteRequest request -> handleRequestVote(request);
            case RequestVoteResponse response -> handleRequestVoteResponse(response);
            case AppendEntriesRequest request -> handleAppendEntries(request);
            case AppendEntriesResponse response -> handleAppendEntriesResponse(response);
            case TimeoutNowRequest ignored -> {}
            case InstallSnapshotRequest ignored -> {}
            case InstallSnapshotResponse ignored -> {}
            case ReadIndexRequest ignored -> {}
            case ReadIndexResponse ignored -> {}
        }
    }

    private static boolean keepsTermOnHigherTerm(RaftMessage message) {
        if (message instanceof RequestVoteRequest request) {
            return request.preVote();
        }
        if (message instanceof RequestVoteResponse response) {
            return response.preVote() && response.voteGranted();
        }
        return false;
    }

    @Nullable
    private static NodeId leaderOf(RaftMessage message) {
        return message instanceof AppendEntriesRequest || message instanceof InstallSnapshotRequest
                ? message.from()
                : null;
    }

    private void replyToStaleSender(RaftMessage message) {
        switch (message) {
            case RequestVoteRequest request ->
                send(new RequestVoteResponse(id(), request.from(), currentTerm, false, request.preVote()));
            case AppendEntriesRequest request ->
                send(AppendEntriesResponse.rejected(id(), request.from(), currentTerm, 0, 0));
            default -> {}
        }
    }

    private void tickElectionTimeout() {
        electionTimer.tick();
        if (electionTimer.hasExpired() && cluster.isVoter(id())) {
            campaign();
        }
    }

    private void tickLeader(Leader leader) {
        heartbeatElapsedTicks++;
        electionTimer.tick();

        if (heartbeatElapsedTicks >= config.heartbeatTicks()) {
            heartbeatElapsedTicks = 0;
            broadcastHeartbeat();
        }

        if (config.checkQuorum() && electionTimer.elapsedTicks() >= config.electionTimeoutMinTicks()) {
            electionTimer.reset();
            if (leader.recentlyActiveCount() < cluster.quorum()) {
                becomeFollower(currentTerm, null);
            } else {
                leader.resetActivity(id());
            }
        }
    }

    @RaftSpec("§5.2 Leader election")
    private void startElection(boolean preVote) {
        if (preVote) {
            state = new Candidate(true);
        } else {
            currentTerm++;
            votedFor = id();
            state = new Candidate(false);
        }
        electionTimer.reset();

        Candidate candidate = (Candidate) state;
        candidate.recordVote(id(), true);
        if (candidate.grantedCount() >= cluster.quorum()) {
            winElection(preVote);
            return;
        }

        long campaignTerm = preVote ? currentTerm + 1 : currentTerm;
        for (NodeId peer : cluster.voters()) {
            if (peer.equals(id())) {
                continue;
            }
            send(new RequestVoteRequest(id(), peer, campaignTerm, log.lastIndex(), lastLogTerm(), preVote));
        }
    }

    private void winElection(boolean preVote) {
        if (preVote) {
            startElection(false);
        } else {
            becomeLeader();
        }
    }

    @RaftSpec(value = "§9.6 Preventing disruptions when a server rejoins", source = RaftSpec.Source.DISSERTATION)
    private boolean isWithinLeaderLease(NodeId candidate) {
        NodeId knownLeader = leaderId();
        return config.checkQuorum()
                && knownLeader != null
                && !knownLeader.equals(candidate)
                && electionTimer.elapsedTicks() < config.electionTimeoutMinTicks();
    }

    @RaftSpec("§5.4.1 Election restriction")
    private void handleRequestVote(RequestVoteRequest request) {
        boolean mayVote = request.preVote()
                ? request.term() > currentTerm
                : (votedFor == null && leaderId() == null) || request.from().equals(votedFor);
        boolean granted = mayVote && isAtLeastAsUpToDate(request.lastLogIndex(), request.lastLogTerm());

        if (granted && !request.preVote()) {
            votedFor = request.from();
            electionTimer.reset();
        }

        long responseTerm = granted ? request.term() : currentTerm;
        send(new RequestVoteResponse(id(), request.from(), responseTerm, granted, request.preVote()));
    }

    private void handleRequestVoteResponse(RequestVoteResponse response) {
        if (!(state instanceof Candidate candidate) || candidate.isPreVote() != response.preVote()) {
            return;
        }
        candidate.recordVote(response.from(), response.voteGranted());

        if (candidate.grantedCount() >= cluster.quorum()) {
            winElection(candidate.isPreVote());
        } else if (cluster.voters().size() - candidate.rejectedCount() < cluster.quorum()) {
            becomeFollower(currentTerm, null);
        }
    }

    @RaftSpec("§5.3 Log replication")
    private void handleAppendEntries(AppendEntriesRequest request) {
        becomeFollower(currentTerm, request.from());

        long prevIndex = request.prevLogIndex();
        if (prevIndex > log.lastIndex()) {
            send(AppendEntriesResponse.rejected(id(), request.from(), currentTerm, log.lastIndex() + 1, 0));
            return;
        }
        if (log.termAt(prevIndex) != request.prevLogTerm()) {
            send(AppendEntriesResponse.rejected(id(), request.from(), currentTerm, prevIndex, log.termAt(prevIndex)));
            return;
        }
        send(AppendEntriesResponse.accepted(id(), request.from(), currentTerm, request.lastIndex()));
    }

    private void handleAppendEntriesResponse(AppendEntriesResponse response) {
        if (state instanceof Leader leader) {
            leader.markActive(response.from());
        }
    }

    private void becomeFollower(long term, @Nullable NodeId leader) {
        if (term > currentTerm) {
            currentTerm = term;
            votedFor = null;
        }
        state = new Follower(leader);
        electionTimer.reset();
        heartbeatElapsedTicks = 0;
    }

    private void becomeLeader() {
        Leader leader = new Leader();
        leader.markActive(id());
        state = leader;
        electionTimer.reset();
        heartbeatElapsedTicks = 0;
        broadcastHeartbeat();
    }

    private void broadcastHeartbeat() {
        for (NodeId peer : cluster.allMembers()) {
            if (peer.equals(id())) {
                continue;
            }
            send(new AppendEntriesRequest(
                    id(), peer, currentTerm, log.lastIndex(), lastLogTerm(), List.of(), commitIndex));
        }
    }

    private boolean isAtLeastAsUpToDate(long candidateLastIndex, long candidateLastTerm) {
        long ownLastTerm = lastLogTerm();
        if (candidateLastTerm != ownLastTerm) {
            return candidateLastTerm > ownLastTerm;
        }
        return candidateLastIndex >= log.lastIndex();
    }

    private long lastLogTerm() {
        return log.termAt(log.lastIndex());
    }

    @Nullable
    private NodeId leaderId() {
        return switch (state) {
            case Follower follower -> follower.leaderId();
            case Candidate ignored -> null;
            case Leader ignored -> id();
        };
    }

    private HardState currentHardState() {
        return new HardState(currentTerm, votedFor, commitIndex);
    }

    private SoftState currentSoftState() {
        return new SoftState(leaderId(), state.role());
    }

    private void send(RaftMessage message) {
        pending.send(message);
    }

    @Override
    public String toString() {
        return "RaftNode[" + id() + " term=" + currentTerm + " role=" + state.role() + "]";
    }
}
