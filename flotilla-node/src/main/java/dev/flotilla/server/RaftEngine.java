/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.server;

import dev.flotilla.core.Bytes;
import dev.flotilla.core.CatchUpStatus;
import dev.flotilla.core.ClusterConfig;
import dev.flotilla.core.ConfChangeResult;
import dev.flotilla.core.LogEntry;
import dev.flotilla.core.NodeId;
import dev.flotilla.core.RaftNode;
import dev.flotilla.core.RaftRole;
import dev.flotilla.core.ReadState;
import dev.flotilla.core.Ready;
import dev.flotilla.core.SoftState;
import dev.flotilla.core.TransferResult;
import dev.flotilla.core.port.StableStore;
import dev.flotilla.storage.DurableLogStore;
import dev.flotilla.storage.WritableSnapshotStore;
import dev.flotilla.transport.ClusterStatus;
import dev.flotilla.transport.ReadConsistency;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;

final class RaftEngine implements Runnable {

    private final RaftNode raft;
    private final DurableLogStore log;
    private final StableStore stable;
    private final WritableSnapshotStore snapshots;
    private final MessageSink sink;
    private final EventQueue events;
    private final ApplyLoop apply;
    private final ProposalRegistry proposals;
    private final int maxBatchSize;
    private final List<NodeEvent> batch = new ArrayList<>();
    private final Map<Bytes, PendingRead> pendingReads = new HashMap<>();
    private final int readTimeoutTicks;

    private long ticks;
    private long readSequence;

    private record PendingRead(Bytes query, CompletableFuture<Applied> result, long registeredAtTick) {}

    private record PendingTransfer(NodeId target, CompletableFuture<NodeId> result, long startedAtTick) {}

    @Nullable
    private PendingTransfer transfer;

    private record PendingChange(NodeEvent.Membership request, long firstTriedAtTick) {}

    private final List<PendingChange> waitingChanges = new ArrayList<>();

    private volatile boolean running = true;
    private volatile boolean leader;
    private volatile long publishedTerm;
    private volatile long publishedCommitIndex;
    private volatile ClusterConfig publishedConfiguration;
    private final AtomicLong syncs = new AtomicLong();
    private final AtomicLong persistedEntries = new AtomicLong();
    private final AtomicLong batches = new AtomicLong();
    private final AtomicInteger largestBatch = new AtomicInteger();
    private final AtomicLong compactions = new AtomicLong();

    @Nullable
    private volatile RuntimeException failure;

    RaftEngine(
            RaftNode raft,
            DurableLogStore log,
            StableStore stable,
            WritableSnapshotStore snapshots,
            MessageSink sink,
            EventQueue events,
            ApplyLoop apply,
            ProposalRegistry proposals,
            int maxBatchSize,
            int readTimeoutTicks) {
        this.raft = Objects.requireNonNull(raft, "raft");
        this.log = Objects.requireNonNull(log, "log");
        this.stable = Objects.requireNonNull(stable, "stable");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.sink = Objects.requireNonNull(sink, "sink");
        this.events = Objects.requireNonNull(events, "events");
        this.apply = Objects.requireNonNull(apply, "apply");
        this.proposals = Objects.requireNonNull(proposals, "proposals");
        this.maxBatchSize = maxBatchSize;
        this.readTimeoutTicks = readTimeoutTicks;
        this.publishedTerm = raft.currentTerm();
        this.publishedCommitIndex = raft.commitIndex();
        this.publishedConfiguration = raft.configuration();
    }

    @Override
    public void run() {
        try {
            while (running) {
                batch.clear();
                batch.add(events.take());
                events.drainTo(batch, maxBatchSize - 1);
                batches.incrementAndGet();
                largestBatch.accumulateAndGet(batch.size(), Math::max);
                for (NodeEvent event : batch) {
                    handle(event);
                }
                processReady();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException unexpected) {
            failure = unexpected;
        } finally {
            running = false;
            leader = false;
            failPending(batch);
            failQueuedProposals(events);
            abandonReads(null);
            abandonTransfer();
            abandonWaitingChanges();
        }
    }

    static void failQueuedProposals(EventQueue events) {
        failPending(events.drain());
    }

    private static void failPending(List<NodeEvent> events) {
        IllegalStateException stopped = new IllegalStateException("the server stopped before this request was taken");
        for (NodeEvent event : events) {
            switch (event) {
                case NodeEvent.Proposal proposal -> proposal.result().completeExceptionally(stopped);
                case NodeEvent.Membership membership -> membership.result().completeExceptionally(stopped);
                case NodeEvent.Transfer handover -> handover.result().completeExceptionally(stopped);
                case NodeEvent.Describe describe -> describe.result().completeExceptionally(stopped);
                default -> {}
            }
        }
    }

    private void abandonWaitingChanges() {
        IllegalStateException stopped = new IllegalStateException("the server stopped before the change was made");
        waitingChanges.forEach(waiting -> waiting.request().result().completeExceptionally(stopped));
        waitingChanges.clear();
    }

    private void abandonTransfer() {
        PendingTransfer pending = transfer;
        transfer = null;
        if (pending != null) {
            pending.result().completeExceptionally(new IllegalStateException("the server stopped during the handover"));
        }
    }

    void stop() {
        running = false;
    }

    boolean isRunning() {
        return running;
    }

    boolean isLeader() {
        return leader;
    }

    long currentTerm() {
        return publishedTerm;
    }

    long commitIndex() {
        return publishedCommitIndex;
    }

    ClusterConfig configuration() {
        return publishedConfiguration;
    }

    long syncs() {
        return syncs.get();
    }

    long persistedEntries() {
        return persistedEntries.get();
    }

    long batches() {
        return batches.get();
    }

    int largestBatch() {
        return largestBatch.get();
    }

    long compactions() {
        return compactions.get();
    }

    Optional<RuntimeException> failure() {
        return Optional.ofNullable(failure);
    }

    private void handle(NodeEvent event) {
        switch (event) {
            case NodeEvent.Tick ignored -> {
                ticks++;
                raft.tick();
                expireStaleReads();
                settleTransfer();
                waitingChanges.removeIf(this::tryChange);
            }
            case NodeEvent.Read read -> read(read);
            case NodeEvent.Inbound inbound -> raft.step(inbound.message());
            case NodeEvent.Proposal proposal -> propose(proposal);
            case NodeEvent.Shutdown ignored -> running = false;
            case NodeEvent.Compact compact -> compact(compact.throughIndex());
            case NodeEvent.Membership membership -> changeMembership(membership);
            case NodeEvent.Transfer handover -> transferLeadership(handover);
            case NodeEvent.Describe describe -> describe(describe);
        }
    }

    private void changeMembership(NodeEvent.Membership membership) {
        PendingChange pending = new PendingChange(membership, ticks);
        if (!tryChange(pending)) {
            waitingChanges.add(pending);
        }
    }

    private boolean tryChange(PendingChange pending) {
        NodeEvent.Membership membership = pending.request();
        if (!raft.isLeader()) {
            membership
                    .result()
                    .completeExceptionally(new NotLeaderException(raft.leader().orElse(null)));
            return true;
        }
        long term = raft.currentTerm();
        switch (raft.proposeConfChange(membership.change())) {
            case ConfChangeResult.Rejected rejected
            when rejected.temporary() && ticks - pending.firstTriedAtTick() < readTimeoutTicks -> {
                return false;
            }
            case ConfChangeResult.Rejected rejected ->
                membership.result().completeExceptionally(new ChangeRejectedException(rejected.reason()));
            case ConfChangeResult.Accepted accepted -> {
                CompletableFuture<Applied> applied = new CompletableFuture<>();
                proposals.register(term, accepted.index(), applied);
                CompletableFuture<Applied> _ = applied.whenComplete((ignored, failure) -> {
                    if (failure == null) {
                        membership.result().complete(accepted.configuration());
                    } else {
                        membership.result().completeExceptionally(failure);
                    }
                });
            }
        }
        return true;
    }

    private void transferLeadership(NodeEvent.Transfer handover) {
        if (!raft.isLeader()) {
            handover.result()
                    .completeExceptionally(new NotLeaderException(raft.leader().orElse(null)));
            return;
        }
        switch (raft.transferLeadership(handover.target())) {
            case TransferResult.Rejected rejected ->
                handover.result().completeExceptionally(new ChangeRejectedException(rejected.reason()));
            case TransferResult.Started started -> {
                abandonTransfer();
                transfer = new PendingTransfer(started.target(), handover.result(), ticks);
            }
        }
    }

    private void settleTransfer() {
        PendingTransfer pending = transfer;
        if (pending == null) {
            return;
        }
        Optional<NodeId> leaderNow = raft.leader();
        String failure;
        if (leaderNow.isPresent() && leaderNow.get().equals(pending.target())) {
            transfer = null;
            pending.result().complete(pending.target());
            return;
        } else if (raft.isLeader() && raft.transferee().isEmpty()) {
            failure = pending.target() + " did not take over within an election timeout, so " + raft.id()
                    + " kept the leadership and accepts writes again";
        } else if (leaderNow.isPresent() && !raft.isLeader()) {
            failure = "leadership went to " + leaderNow.get() + " instead of " + pending.target();
        } else if (ticks - pending.startedAtTick() >= readTimeoutTicks) {
            failure = "no leader emerged within " + readTimeoutTicks + " ticks of the handover to " + pending.target();
        } else {
            return;
        }
        transfer = null;
        pending.result().completeExceptionally(new ChangeRejectedException(failure));
    }

    private void describe(NodeEvent.Describe describe) {
        if (describe.leaderOnly() && !raft.isLeader()) {
            describe.result()
                    .completeExceptionally(new NotLeaderException(raft.leader().orElse(null)));
        } else {
            describe.result().complete(status());
        }
    }

    private ClusterStatus status() {
        ClusterConfig configuration = raft.configuration();
        SortedMap<NodeId, CatchUpStatus> catchUp = new TreeMap<>();
        for (NodeId learner : configuration.learners()) {
            raft.catchUpStatus(learner).ifPresent(status -> catchUp.put(learner, status));
        }
        return new ClusterStatus(
                raft.id(),
                raft.leader().orElse(null),
                raft.currentTerm(),
                configuration,
                raft.configurationIndex(),
                raft.configurationIndex() <= raft.commitIndex(),
                raft.commitIndex(),
                apply.appliedIndex(),
                catchUp);
    }

    private void compact(long throughIndex) {
        if (throughIndex >= raft.firstLogIndex()) {
            raft.compactLog(throughIndex);
            compactions.incrementAndGet();
        }
    }

    private void propose(NodeEvent.Proposal proposal) {
        if (!raft.isLeader()) {
            proposal.result()
                    .completeExceptionally(new NotLeaderException(raft.leader().orElse(null)));
            return;
        }
        long term = raft.currentTerm();
        long index = raft.lastLogIndex() + 1;
        if (!raft.propose(proposal.command())) {
            proposal.result()
                    .completeExceptionally(new NotLeaderException(
                            raft.transferee().or(raft::leader).orElse(null)));
            return;
        }
        proposals.register(term, index, proposal.result());
    }

    private void processReady() throws InterruptedException {
        Ready ready = raft.ready();
        if (ready.isEmpty()) {
            return;
        }
        if (ready.requiresSync()) {
            ready.hardState().ifPresent(stable::persist);
            ready.snapshot().ifPresent(snapshots::save);
            log.sync();
            syncs.incrementAndGet();
            persistedEntries.addAndGet(ready.entriesToPersist().size());
        }
        for (var message : ready.messagesToSend()) {
            sink.send(message);
        }
        List<LogEntry> committed = ready.committedEntriesToApply();
        ready.softState().ifPresent(this::publishSoftState);
        raft.advance();
        publishedTerm = raft.currentTerm();
        publishedCommitIndex = raft.commitIndex();
        publishedConfiguration = raft.configuration();
        apply.submit(ready.snapshotToInstall(), committed);
        answerConfirmedReads(ready.readStates());
        ready.softState().ifPresent(ignored -> abandonReads(raft.leader().orElse(null)));
        settleTransfer();
    }

    private void read(NodeEvent.Read read) {
        readSequence++;
        Bytes requestId =
                Bytes.wrap(ByteBuffer.allocate(Long.BYTES).putLong(readSequence).array());
        boolean accepted =
                read.consistency() == ReadConsistency.LEASE ? raft.leaseRead(requestId) : raft.readIndex(requestId);
        if (!accepted) {
            read.result()
                    .completeExceptionally(new NotLeaderException(raft.leader().orElse(null)));
            return;
        }
        pendingReads.put(requestId, new PendingRead(read.query(), read.result(), ticks));
    }

    private void answerConfirmedReads(List<ReadState> confirmed) {
        for (ReadState readState : confirmed) {
            PendingRead pending = pendingReads.remove(readState.requestId());
            if (pending != null && !apply.offerQuery(readState.readIndex(), pending.query(), pending.result())) {
                pending.result()
                        .completeExceptionally(new BackpressureException(
                                "the apply queue is full; the read was refused rather than buffered"));
            }
        }
    }

    private void abandonReads(@Nullable NodeId knownLeader) {
        if (pendingReads.isEmpty()) {
            return;
        }
        NotLeaderException moved = new NotLeaderException(knownLeader);
        pendingReads.values().forEach(pending -> pending.result().completeExceptionally(moved));
        pendingReads.clear();
    }

    private void expireStaleReads() {
        pendingReads.values().removeIf(pending -> {
            if (ticks - pending.registeredAtTick() < readTimeoutTicks) {
                return false;
            }
            pending.result()
                    .completeExceptionally(new ReadTimeoutException(
                            "no leader confirmed this read within " + readTimeoutTicks + " ticks"));
            return true;
        });
    }

    private void publishSoftState(SoftState state) {
        boolean nowLeader = state.role() == RaftRole.LEADER;
        if (leader && !nowLeader) {
            proposals.failAbove(raft.commitIndex(), state.leaderId());
        }
        leader = nowLeader;
    }
}
