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
import dev.flotilla.core.port.SnapshotStore;
import dev.flotilla.core.state.Candidate;
import dev.flotilla.core.state.Follower;
import dev.flotilla.core.state.Leader;
import dev.flotilla.core.state.Progress;
import dev.flotilla.core.state.ProgressState;
import dev.flotilla.core.state.RaftState;
import dev.flotilla.core.state.ReadIndexQueue;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

@RaftSpec("Figure 2, Rules for Servers")
public final class RaftNode {

    private final RaftConfig config;
    private final ClusterConfig cluster;
    private final LogStore log;
    private final SnapshotStore snapshots;
    private final ElectionTimer electionTimer;

    private final List<RaftMessage> outbox = new ArrayList<>();
    private final List<LogEntry> unpersisted = new ArrayList<>();
    private final List<ReadState> readStates = new ArrayList<>();

    private RaftState state;
    private long currentTerm;

    @Nullable
    private NodeId votedFor;

    private long commitIndex;
    private long emittedCommitIndex;
    private int heartbeatElapsedTicks;
    private long ticksSinceStart;

    @Nullable
    private Snapshot pendingSnapshot;

    private HardState publishedHardState;
    private SoftState publishedSoftState;

    public RaftNode(
            RaftConfig config,
            ClusterConfig cluster,
            LogStore log,
            SnapshotStore snapshots,
            RandomSource random,
            HardState persisted) {
        this.config = Objects.requireNonNull(config, "config");
        this.cluster = Objects.requireNonNull(cluster, "cluster");
        this.log = Objects.requireNonNull(log, "log");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        Objects.requireNonNull(persisted, "persisted");
        if (!cluster.contains(config.nodeId())) {
            throw new IllegalArgumentException(
                    config.nodeId() + " is not a member of the cluster " + cluster.allMembers());
        }
        this.electionTimer = new ElectionTimer(config, Objects.requireNonNull(random, "random"));
        this.currentTerm = persisted.currentTerm();
        this.votedFor = persisted.votedFor();
        this.commitIndex = Math.max(persisted.commitIndex(), log.firstIndex() - 1);
        this.emittedCommitIndex = this.commitIndex;
        this.state = Follower.withoutLeader();
        this.publishedHardState = persisted;
        this.publishedSoftState = SoftState.follower();
    }

    public static RaftNode bootstrap(RaftConfig config, ClusterConfig cluster, LogStore log, RandomSource random) {
        return new RaftNode(config, cluster, log, SnapshotStore.none(), random, HardState.INITIAL);
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

    public long lastLogIndex() {
        return log.lastIndex();
    }

    public long firstLogIndex() {
        return log.firstIndex();
    }

    public boolean isLeader() {
        return state instanceof Leader;
    }

    public boolean propose(Bytes command) {
        Objects.requireNonNull(command, "command");
        if (!(state instanceof Leader)) {
            return false;
        }
        appendToOwnLog(List.of(LogEntry.normal(currentTerm, log.lastIndex() + 1, command)));
        broadcastAppend();
        return true;
    }

    @RaftSpec(value = "§6.4 Processing read-only queries more efficiently", source = RaftSpec.Source.DISSERTATION)
    public boolean readIndex(Bytes requestId) {
        Objects.requireNonNull(requestId, "requestId");
        return switch (state) {
            case Leader leader -> {
                leader.reads().enqueue(new ReadIndexQueue.PendingRead(requestId, null));
                startReadRoundIfPossible(leader);
                yield true;
            }
            case Follower follower -> {
                NodeId knownLeader = follower.leaderId();
                if (knownLeader == null) {
                    yield false;
                }
                send(new ReadIndexRequest(id(), knownLeader, currentTerm, requestId));
                yield true;
            }
            case Candidate ignored -> false;
        };
    }

    @RaftSpec(
            value = "§6.4.1 Using clocks to reduce messaging for read-only queries",
            source = RaftSpec.Source.DISSERTATION)
    public boolean leaseRead(Bytes requestId) {
        Objects.requireNonNull(requestId, "requestId");
        if (state instanceof Leader leader
                && config.leaseReads()
                && hasCommittedInCurrentTerm()
                && holdsLease(leader)) {
            readStates.add(new ReadState(requestId, commitIndex));
            return true;
        }
        return readIndex(requestId);
    }

    private boolean holdsLease(Leader leader) {
        long confirmed = leader.quorumAckedRound(cluster.voters(), id(), cluster.quorum());
        return leader.ticksSinceRoundWasSent(confirmed) < config.leaseTicks();
    }

    private boolean hasCommittedInCurrentTerm() {
        return log.termAt(commitIndex) == currentTerm;
    }

    private void startReadRoundIfPossible(Leader leader) {
        if (!leader.reads().canStartRound() || !hasCommittedInCurrentTerm()) {
            return;
        }
        leader.reads().startRound(leader.round() + 1, commitIndex);
        broadcastHeartbeat(leader);
        releaseConfirmedReads(leader);
    }

    private void releaseConfirmedReads(Leader leader) {
        long confirmed = leader.quorumAckedRound(cluster.voters(), id(), cluster.quorum());
        for (ReadIndexQueue.ConfirmedRead read : leader.reads().confirmThrough(confirmed)) {
            NodeId origin = read.origin();
            if (origin == null) {
                readStates.add(new ReadState(read.requestId(), read.readIndex()));
            } else {
                send(new ReadIndexResponse(id(), origin, currentTerm, read.requestId(), read.readIndex()));
            }
        }
        startReadRoundIfPossible(leader);
    }

    private void handleReadIndexRequest(ReadIndexRequest request) {
        if (state instanceof Leader leader) {
            leader.reads().enqueue(new ReadIndexQueue.PendingRead(request.requestId(), request.from()));
            startReadRoundIfPossible(leader);
        }
    }

    private void handleReadIndexResponse(ReadIndexResponse response) {
        if (state instanceof Follower) {
            readStates.add(new ReadState(response.requestId(), response.readIndex()));
        }
    }

    @RaftSpec("§7 Log compaction")
    public void compactLog(long throughIndex) {
        long covered = snapshots.latest().map(Snapshot::lastIncludedIndex).orElse(0L);
        if (throughIndex > covered) {
            throw new IllegalArgumentException("Cannot compact the log through index " + throughIndex
                    + "; the newest snapshot only covers index " + covered
                    + ". A follower that falls behind a compacted prefix can only be caught up from a snapshot, "
                    + "so the snapshot has to exist before the entries go away.");
        }
        if (throughIndex > emittedCommitIndex) {
            throw new IllegalArgumentException("Cannot compact the log through index " + throughIndex
                    + "; only entries up to index " + emittedCommitIndex + " have been handed to the state machine.");
        }
        log.compactTo(throughIndex);
    }

    public void tick() {
        if (ticksSinceStart < Long.MAX_VALUE) {
            ticksSinceStart++;
        }
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
        Ready.Builder builder = Ready.builder();
        builder.persistAll(unpersisted);
        for (RaftMessage message : outbox) {
            builder.send(message);
        }
        for (ReadState readState : readStates) {
            builder.readState(readState);
        }
        if (pendingSnapshot != null) {
            builder.install(pendingSnapshot);
        }
        if (commitIndex > emittedCommitIndex) {
            int count = (int) Math.min(Integer.MAX_VALUE, commitIndex - emittedCommitIndex);
            builder.apply(log.entriesFrom(emittedCommitIndex + 1, count, Long.MAX_VALUE));
        }
        HardState hardState = currentHardState();
        if (!hardState.equals(publishedHardState)) {
            builder.hardState(hardState);
        }
        SoftState softState = currentSoftState();
        if (!softState.equals(publishedSoftState)) {
            builder.softState(softState);
        }
        return builder.build();
    }

    public void advance() {
        outbox.clear();
        unpersisted.clear();
        readStates.clear();
        pendingSnapshot = null;
        emittedCommitIndex = commitIndex;
        publishedHardState = currentHardState();
        publishedSoftState = currentSoftState();
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
            case InstallSnapshotRequest request -> handleInstallSnapshot(request);
            case InstallSnapshotResponse response -> handleInstallSnapshotResponse(response);
            case TimeoutNowRequest ignored -> {}
            case ReadIndexRequest request -> handleReadIndexRequest(request);
            case ReadIndexResponse response -> handleReadIndexResponse(response);
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
                send(AppendEntriesResponse.rejected(id(), request.from(), currentTerm, 0, 0, 0));
            case InstallSnapshotRequest request ->
                send(new InstallSnapshotResponse(id(), request.from(), currentTerm, 0, false));
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
        leader.tick();
        heartbeatElapsedTicks++;
        electionTimer.tick();
        resendSnapshotsThatWentUnanswered(leader);

        if (heartbeatElapsedTicks >= config.heartbeatTicks()) {
            heartbeatElapsedTicks = 0;
            broadcastHeartbeat(leader);
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

    @RaftSpec("§7 Log compaction")
    private void resendSnapshotsThatWentUnanswered(Leader leader) {
        for (Map.Entry<NodeId, Progress> peer : leader.peers().entrySet()) {
            Progress progress = peer.getValue();
            progress.recordSnapshotTick();
            if (progress.snapshotTimedOut(config.snapshotTimeoutTicks())) {
                progress.becomeProbe();
                replicateTo(peer.getKey(), progress);
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
        boolean granted = mayVote
                && !isInStartupQuietPeriod()
                && isAtLeastAsUpToDate(request.lastLogIndex(), request.lastLogTerm());

        if (granted && !request.preVote()) {
            votedFor = request.from();
            electionTimer.reset();
        }

        long responseTerm = granted ? request.term() : currentTerm;
        send(new RequestVoteResponse(id(), request.from(), responseTerm, granted, request.preVote()));
    }

    @RaftSpec(
            value = "§6.4.1 Using clocks to reduce messaging for read-only queries",
            source = RaftSpec.Source.DISSERTATION)
    private boolean isInStartupQuietPeriod() {
        return config.leaseReads() && ticksSinceStart < config.electionTimeoutMinTicks();
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

    @RaftSpec("Figure 2, AppendEntries RPC")
    private void handleAppendEntries(AppendEntriesRequest request) {
        becomeFollower(currentTerm, request.from());

        long prevIndex = request.prevLogIndex();
        long round = request.round();
        long snapshotIndex = log.firstIndex() - 1;
        if (prevIndex < snapshotIndex) {
            send(AppendEntriesResponse.accepted(id(), request.from(), currentTerm, snapshotIndex, round));
            return;
        }
        if (prevIndex > log.lastIndex()) {
            send(AppendEntriesResponse.rejected(id(), request.from(), currentTerm, log.lastIndex() + 1, 0, round));
            return;
        }
        long localPrevTerm = log.termAt(prevIndex);
        if (localPrevTerm != request.prevLogTerm()) {
            send(AppendEntriesResponse.rejected(
                    id(),
                    request.from(),
                    currentTerm,
                    firstIndexOfTermEndingAt(prevIndex, localPrevTerm),
                    localPrevTerm,
                    round));
            return;
        }

        long lastNewIndex = storeEntries(request.entries(), prevIndex);
        advanceFollowerCommit(request.leaderCommit(), lastNewIndex);
        send(AppendEntriesResponse.accepted(id(), request.from(), currentTerm, lastNewIndex, round));
    }

    @RaftSpec("Figure 13, InstallSnapshot RPC")
    private void handleInstallSnapshot(InstallSnapshotRequest request) {
        becomeFollower(currentTerm, request.from());

        Snapshot snapshot = request.snapshot();
        long index = snapshot.lastIncludedIndex();
        if (index <= commitIndex) {
            send(new InstallSnapshotResponse(id(), request.from(), currentTerm, commitIndex, false));
            return;
        }
        if (index <= log.lastIndex() && log.termAt(index) == snapshot.lastIncludedTerm()) {
            commitIndex = index;
            send(new InstallSnapshotResponse(id(), request.from(), currentTerm, index, false));
            return;
        }

        log.resetTo(index, snapshot.lastIncludedTerm());
        unpersisted.clear();
        commitIndex = index;
        emittedCommitIndex = index;
        pendingSnapshot = snapshot;
        send(new InstallSnapshotResponse(id(), request.from(), currentTerm, index, true));
    }

    private long storeEntries(List<LogEntry> entries, long prevIndex) {
        if (entries.isEmpty()) {
            return prevIndex;
        }
        long conflictIndex = 0;
        for (LogEntry entry : entries) {
            if (entry.index() > log.lastIndex()) {
                break;
            }
            if (log.termAt(entry.index()) != entry.term()) {
                conflictIndex = entry.index();
                break;
            }
        }
        if (conflictIndex > 0) {
            log.truncateSuffixFrom(conflictIndex);
        }
        long firstMissing = log.lastIndex() + 1;
        List<LogEntry> toAppend =
                entries.stream().filter(entry -> entry.index() >= firstMissing).toList();
        if (!toAppend.isEmpty()) {
            log.append(toAppend);
            unpersisted.addAll(toAppend);
        }
        return entries.getLast().index();
    }

    @RaftSpec("Figure 2, AppendEntries RPC rule 5")
    private void advanceFollowerCommit(long leaderCommit, long lastNewIndex) {
        commitIndex = Math.max(commitIndex, Math.min(leaderCommit, lastNewIndex));
    }

    private void handleAppendEntriesResponse(AppendEntriesResponse response) {
        if (!(state instanceof Leader leader)) {
            return;
        }
        leader.markActive(response.from());
        Progress progress = leader.progressFor(response.from());
        if (progress == null) {
            return;
        }
        progress.recordReply();
        leader.recordAck(response.from(), response.round());
        releaseConfirmedReads(leader);

        if (response.success()) {
            boolean advanced = progress.maybeUpdate(response.matchIndex());
            leaveSnapshotStateIfCaughtUp(progress);
            if (progress.state() == ProgressState.PROBE) {
                progress.becomeReplicate();
            }
            if (advanced) {
                maybeAdvanceLeaderCommit(leader);
            }
        } else if (progress.state() != ProgressState.SNAPSHOT) {
            progress.resetNextIndex(nextIndexAfterRejection(response));
            progress.becomeProbe();
        }
        replicateTo(response.from(), progress);
    }

    @RaftSpec("§7 Log compaction")
    private void handleInstallSnapshotResponse(InstallSnapshotResponse response) {
        if (!(state instanceof Leader leader)) {
            return;
        }
        leader.markActive(response.from());
        Progress progress = leader.progressFor(response.from());
        if (progress == null) {
            return;
        }
        progress.recordReply();

        boolean advanced = progress.maybeUpdate(response.matchIndex());
        if (progress.state() == ProgressState.SNAPSHOT) {
            long pending = progress.pendingSnapshotIndex();
            progress.becomeProbe();
            if (progress.matchIndex() >= pending) {
                progress.becomeReplicate();
            } else {
                progress.resetNextIndex(response.matchIndex() + 1);
            }
        }
        if (advanced) {
            maybeAdvanceLeaderCommit(leader);
        }
        replicateTo(response.from(), progress);
    }

    private static void leaveSnapshotStateIfCaughtUp(Progress progress) {
        if (progress.state() == ProgressState.SNAPSHOT && progress.matchIndex() >= progress.pendingSnapshotIndex()) {
            progress.becomeReplicate();
        }
    }

    @RaftSpec("§5.3 Log replication")
    private long nextIndexAfterRejection(AppendEntriesResponse response) {
        if (response.conflictTerm() > 0) {
            long lastMatching = lastIndexOfTerm(response.conflictTerm());
            if (lastMatching > 0) {
                return lastMatching + 1;
            }
        }
        return response.conflictIndex();
    }

    private long lastIndexOfTerm(long term) {
        for (long index = log.lastIndex(); index >= log.firstIndex(); index--) {
            long indexTerm = log.termAt(index);
            if (indexTerm == term) {
                return index;
            }
            if (indexTerm < term) {
                return 0;
            }
        }
        return 0;
    }

    private long firstIndexOfTermEndingAt(long index, long term) {
        long first = index;
        while (first > log.firstIndex() && log.termAt(first - 1) == term) {
            first--;
        }
        return first;
    }

    @RaftSpec("§5.4.2 Committing entries from previous terms")
    private void maybeAdvanceLeaderCommit(Leader leader) {
        List<Long> matched = new ArrayList<>();
        for (NodeId voter : cluster.voters()) {
            if (voter.equals(id())) {
                matched.add(log.lastIndex());
            } else {
                Progress progress = leader.progressFor(voter);
                matched.add(progress == null ? 0L : progress.matchIndex());
            }
        }
        matched.sort(Comparator.reverseOrder());
        long replicatedOnQuorum = matched.get(cluster.quorum() - 1);

        if (replicatedOnQuorum > commitIndex && log.termAt(replicatedOnQuorum) == currentTerm) {
            commitIndex = replicatedOnQuorum;
            startReadRoundIfPossible(leader);
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
        for (NodeId peer : cluster.allMembers()) {
            if (!peer.equals(id())) {
                leader.trackPeer(peer, log.lastIndex() + 1);
            }
        }
        state = leader;
        electionTimer.reset();
        heartbeatElapsedTicks = 0;

        appendToOwnLog(List.of(LogEntry.noOp(currentTerm, log.lastIndex() + 1)));
        broadcastAppend();
    }

    private void appendToOwnLog(List<LogEntry> entries) {
        log.append(entries);
        unpersisted.addAll(entries);
        if (state instanceof Leader leader) {
            maybeAdvanceLeaderCommit(leader);
        }
    }

    private void broadcastAppend() {
        if (!(state instanceof Leader leader)) {
            return;
        }
        for (Map.Entry<NodeId, Progress> peer : leader.peers().entrySet()) {
            replicateTo(peer.getKey(), peer.getValue());
        }
    }

    private void broadcastHeartbeat(Leader leader) {
        long round = leader.nextRound();
        for (Map.Entry<NodeId, Progress> peer : leader.peers().entrySet()) {
            long prevIndex = Math.max(peer.getValue().matchIndex(), log.firstIndex() - 1);
            send(new AppendEntriesRequest(
                    id(), peer.getKey(), currentTerm, prevIndex, log.termAt(prevIndex), List.of(), commitIndex, round));
        }
    }

    private void replicateTo(NodeId peer, Progress progress) {
        if (progress.state() == ProgressState.SNAPSHOT) {
            return;
        }
        if (progress.nextIndex() < log.firstIndex()) {
            sendSnapshot(peer, progress);
        } else if (progress.nextIndex() <= log.lastIndex()) {
            sendAppend(peer, progress);
        }
    }

    @RaftSpec("§7 Log compaction")
    private void sendSnapshot(NodeId peer, Progress progress) {
        Optional<Snapshot> available = snapshots.latest();
        if (available.isEmpty() || !available.get().covers(log.firstIndex() - 1)) {
            return;
        }
        Snapshot snapshot = available.get();
        send(new InstallSnapshotRequest(id(), peer, currentTerm, snapshot));
        progress.becomeSnapshot(snapshot.lastIncludedIndex());
    }

    private void sendAppend(NodeId peer, Progress progress) {
        if (!(state instanceof Leader leader) || progress.isThrottled(config.maxInflightAppends())) {
            return;
        }
        long prevIndex = progress.nextIndex() - 1;
        int maxEntries = progress.state() == ProgressState.PROBE ? 1 : config.maxEntriesPerAppend();
        List<LogEntry> entries = progress.nextIndex() > log.lastIndex()
                ? List.of()
                : log.entriesFrom(progress.nextIndex(), maxEntries, config.maxAppendBytes());

        send(new AppendEntriesRequest(
                id(), peer, currentTerm, prevIndex, log.termAt(prevIndex), entries, commitIndex, leader.round()));
        progress.recordSend();
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
        outbox.add(message);
    }

    @Override
    public String toString() {
        return "RaftNode[" + id() + " term=" + currentTerm + " role=" + state.role() + " commit=" + commitIndex + "]";
    }
}
