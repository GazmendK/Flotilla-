/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.server;

import dev.flotilla.core.LogEntry;
import dev.flotilla.core.RaftNode;
import dev.flotilla.core.RaftRole;
import dev.flotilla.core.Ready;
import dev.flotilla.core.SoftState;
import dev.flotilla.core.port.StableStore;
import dev.flotilla.storage.DurableLogStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;

final class RaftEngine implements Runnable {

    private final RaftNode raft;
    private final DurableLogStore log;
    private final StableStore stable;
    private final MessageSink sink;
    private final EventQueue events;
    private final ApplyLoop apply;
    private final ProposalRegistry proposals;
    private final int maxBatchSize;
    private final List<NodeEvent> batch = new ArrayList<>();

    private volatile boolean running = true;
    private volatile boolean leader;
    private volatile long publishedTerm;
    private volatile long publishedCommitIndex;
    private final AtomicLong syncs = new AtomicLong();
    private final AtomicLong persistedEntries = new AtomicLong();
    private final AtomicLong batches = new AtomicLong();
    private final AtomicInteger largestBatch = new AtomicInteger();

    @Nullable
    private volatile RuntimeException failure;

    RaftEngine(
            RaftNode raft,
            DurableLogStore log,
            StableStore stable,
            MessageSink sink,
            EventQueue events,
            ApplyLoop apply,
            ProposalRegistry proposals,
            int maxBatchSize) {
        this.raft = Objects.requireNonNull(raft, "raft");
        this.log = Objects.requireNonNull(log, "log");
        this.stable = Objects.requireNonNull(stable, "stable");
        this.sink = Objects.requireNonNull(sink, "sink");
        this.events = Objects.requireNonNull(events, "events");
        this.apply = Objects.requireNonNull(apply, "apply");
        this.proposals = Objects.requireNonNull(proposals, "proposals");
        this.maxBatchSize = maxBatchSize;
        this.publishedTerm = raft.currentTerm();
        this.publishedCommitIndex = raft.commitIndex();
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
        }
    }

    static void failQueuedProposals(EventQueue events) {
        failPending(events.drain());
    }

    private static void failPending(List<NodeEvent> events) {
        for (NodeEvent event : events) {
            if (event instanceof NodeEvent.Proposal proposal) {
                proposal.result()
                        .completeExceptionally(
                                new IllegalStateException("the server stopped before this proposal was accepted"));
            }
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

    Optional<RuntimeException> failure() {
        return Optional.ofNullable(failure);
    }

    private void handle(NodeEvent event) {
        switch (event) {
            case NodeEvent.Tick ignored -> raft.tick();
            case NodeEvent.Inbound inbound -> raft.step(inbound.message());
            case NodeEvent.Proposal proposal -> propose(proposal);
            case NodeEvent.Shutdown ignored -> running = false;
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
                    .completeExceptionally(new NotLeaderException(raft.leader().orElse(null)));
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
        apply.submit(committed);
    }

    private void publishSoftState(SoftState state) {
        boolean nowLeader = state.role() == RaftRole.LEADER;
        if (leader && !nowLeader) {
            proposals.failAll(state.leaderId());
        }
        leader = nowLeader;
    }
}
