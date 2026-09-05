/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.server;

import dev.flotilla.core.EntryType;
import dev.flotilla.core.LogEntry;
import dev.flotilla.core.RaftNode;
import dev.flotilla.core.RaftRole;
import dev.flotilla.core.Ready;
import dev.flotilla.core.SoftState;
import dev.flotilla.core.port.StableStore;
import dev.flotilla.kv.StateMachine;
import dev.flotilla.storage.DurableLogStore;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

final class RaftEngine implements Runnable {

    private final RaftNode raft;
    private final DurableLogStore log;
    private final StableStore stable;
    private final StateMachine stateMachine;
    private final MessageSink sink;
    private final EventQueue events;
    private final ProposalRegistry proposals = new ProposalRegistry();

    private volatile boolean running = true;
    private volatile boolean leader;
    private volatile long publishedTerm;
    private volatile long publishedCommitIndex;
    private volatile long publishedAppliedIndex;

    @Nullable
    private volatile RuntimeException failure;

    private long lastApplied;

    RaftEngine(
            RaftNode raft,
            DurableLogStore log,
            StableStore stable,
            StateMachine stateMachine,
            MessageSink sink,
            EventQueue events) {
        this.raft = Objects.requireNonNull(raft, "raft");
        this.log = Objects.requireNonNull(log, "log");
        this.stable = Objects.requireNonNull(stable, "stable");
        this.stateMachine = Objects.requireNonNull(stateMachine, "stateMachine");
        this.sink = Objects.requireNonNull(sink, "sink");
        this.events = Objects.requireNonNull(events, "events");
        this.publishedTerm = raft.currentTerm();
        this.publishedCommitIndex = raft.commitIndex();
    }

    void replayThrough(long throughIndex) {
        for (long index = Math.max(1, log.firstIndex()); index <= throughIndex; index++) {
            Optional<LogEntry> entry = log.entryAt(index);
            if (entry.isPresent() && entry.get().type() == EntryType.NORMAL) {
                stateMachine.apply(index, entry.get().data());
            }
        }
        lastApplied = Math.max(lastApplied, throughIndex);
        publishedAppliedIndex = lastApplied;
    }

    @Override
    public void run() {
        try {
            while (running) {
                NodeEvent event = events.take();
                handle(event);
                processReady();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException unexpected) {
            failure = unexpected;
        } finally {
            running = false;
            leader = false;
            proposals.failAll(null);
            failQueuedProposals(events);
        }
    }

    static void failQueuedProposals(EventQueue events) {
        for (NodeEvent event : events.drain()) {
            if (event instanceof NodeEvent.Proposal proposal) {
                proposal.result()
                        .completeExceptionally(new IllegalStateException(
                                "the server shut down before this proposal reached the event loop"));
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

    long appliedIndex() {
        return publishedAppliedIndex;
    }

    int pendingProposals() {
        return proposals.size();
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

    private void processReady() {
        Ready ready = raft.ready();
        if (!ready.isEmpty()) {
            if (ready.requiresSync()) {
                ready.hardState().ifPresent(stable::persist);
                log.sync();
            }
            ready.messagesToSend().forEach(sink::send);
            for (LogEntry entry : ready.committedEntriesToApply()) {
                applyEntry(entry);
            }
            ready.softState().ifPresent(this::publishSoftState);
            raft.advance();
        }
        publishedTerm = raft.currentTerm();
        publishedCommitIndex = raft.commitIndex();
        publishedAppliedIndex = lastApplied;
    }

    private void applyEntry(LogEntry entry) {
        if (entry.index() <= lastApplied) {
            return;
        }
        if (entry.type() == EntryType.NORMAL) {
            stateMachine.apply(entry.index(), entry.data());
        }
        lastApplied = entry.index();
        proposals.completeApplied(entry.index(), entry.term());
    }

    private void publishSoftState(SoftState state) {
        boolean nowLeader = state.role() == RaftRole.LEADER;
        if (leader && !nowLeader) {
            proposals.failAll(state.leaderId());
        }
        leader = nowLeader;
    }
}
