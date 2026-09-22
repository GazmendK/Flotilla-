/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.server;

import dev.flotilla.core.Bytes;
import dev.flotilla.core.ClusterConfig;
import dev.flotilla.core.ClusterConfigCodec;
import dev.flotilla.core.EntryType;
import dev.flotilla.core.LogEntry;
import dev.flotilla.core.Snapshot;
import dev.flotilla.core.port.LogStore;
import dev.flotilla.kv.StateMachine;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;

final class ApplyLoop implements Runnable {

    private static final long DRAIN_POLL_MILLIS = 20;

    private final StateMachine stateMachine;
    private final ProposalRegistry proposals;
    private final BlockingQueue<Work> pending;
    private final AtomicLong backlog = new AtomicLong();
    private final AtomicLong restores = new AtomicLong();

    private volatile boolean running = true;
    private volatile long appliedIndex;
    private volatile SnapshotTrigger trigger = SnapshotTrigger.none();
    private volatile ClusterConfig configuration;

    @Nullable
    private volatile RuntimeException failure;

    private sealed interface Work {}

    private record Entries(@Nullable Snapshot snapshot, List<LogEntry> entries) implements Work {}

    private record Query(long readIndex, Bytes query, CompletableFuture<Applied> result) implements Work {}

    private final PriorityQueue<Query> parked = new PriorityQueue<>(Comparator.comparingLong(Query::readIndex));

    ApplyLoop(StateMachine stateMachine, ProposalRegistry proposals, int capacity, ClusterConfig configuration) {
        this.stateMachine = Objects.requireNonNull(stateMachine, "stateMachine");
        this.proposals = Objects.requireNonNull(proposals, "proposals");
        this.pending = new ArrayBlockingQueue<>(capacity);
        this.configuration = Objects.requireNonNull(configuration, "configuration");
    }

    void onApplied(SnapshotTrigger listener) {
        this.trigger = Objects.requireNonNull(listener, "listener");
    }

    void restoreFrom(Snapshot snapshot) {
        stateMachine.restore(snapshot.data());
        appliedIndex = snapshot.lastIncludedIndex();
        configuration = snapshot.cluster();
        restores.incrementAndGet();
    }

    void replayThrough(LogStore log, long throughIndex) {
        for (long index = Math.max(appliedIndex + 1, log.firstIndex()); index <= throughIndex; index++) {
            Optional<LogEntry> entry = log.entryAt(index);
            if (entry.isPresent() && entry.get().type() == EntryType.NORMAL) {
                stateMachine.apply(index, entry.get().data());
            } else if (entry.isPresent() && entry.get().type() == EntryType.CONFIGURATION) {
                configuration = ClusterConfigCodec.decode(entry.get().data());
            }
        }
        appliedIndex = Math.max(appliedIndex, throughIndex);
    }

    void submit(@Nullable Snapshot snapshot, List<LogEntry> committed) throws InterruptedException {
        if (snapshot == null && committed.isEmpty()) {
            return;
        }
        backlog.addAndGet(committed.size());
        pending.put(new Entries(snapshot, committed));
    }

    boolean offerQuery(long readIndex, Bytes query, CompletableFuture<Applied> result) {
        return running && pending.offer(new Query(readIndex, query, result));
    }

    int parkedQueries() {
        return parked.size();
    }

    @Override
    public void run() {
        try {
            while (running || !pending.isEmpty()) {
                Work work = pending.poll(DRAIN_POLL_MILLIS, TimeUnit.MILLISECONDS);
                if (work != null) {
                    applyWork(work);
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException unexpected) {
            failure = unexpected;
        } finally {
            running = false;
            failUnansweredQueries();
        }
    }

    private void failUnansweredQueries() {
        IllegalStateException stopped = new IllegalStateException("the server stopped before this read was answered");
        parked.forEach(query -> query.result().completeExceptionally(stopped));
        parked.clear();
        for (Work work : pending) {
            if (work instanceof Query query) {
                query.result().completeExceptionally(stopped);
            }
        }
    }

    void stop() {
        running = false;
    }

    long appliedIndex() {
        return appliedIndex;
    }

    ClusterConfig appliedConfiguration() {
        return configuration;
    }

    long backlog() {
        return backlog.get();
    }

    long restores() {
        return restores.get();
    }

    Optional<RuntimeException> failure() {
        return Optional.ofNullable(failure);
    }

    private void applyWork(Work work) {
        switch (work) {
            case Entries entries -> {
                Snapshot snapshot = entries.snapshot();
                if (snapshot != null) {
                    restoreFrom(snapshot);
                }
                applyBatch(entries.entries());
                answerParkedQueries();
            }
            case Query query -> {
                if (query.readIndex() <= appliedIndex) {
                    answer(query);
                } else {
                    parked.add(query);
                }
            }
        }
    }

    private void answerParkedQueries() {
        while (!parked.isEmpty() && parked.peek().readIndex() <= appliedIndex) {
            answer(parked.poll());
        }
    }

    private void answer(Query query) {
        try {
            query.result().complete(new Applied(appliedIndex, stateMachine.query(query.query())));
        } catch (RuntimeException rejected) {
            query.result().completeExceptionally(rejected);
        }
    }

    private void applyBatch(List<LogEntry> batch) {
        long bytes = 0;
        long lastTerm = 0;
        for (LogEntry entry : batch) {
            if (entry.index() > appliedIndex) {
                Bytes response = entry.type() == EntryType.NORMAL
                        ? stateMachine.apply(entry.index(), entry.data())
                        : Bytes.EMPTY;
                if (entry.type() == EntryType.CONFIGURATION) {
                    configuration = ClusterConfigCodec.decode(entry.data());
                }
                appliedIndex = entry.index();
                bytes += entry.data().size();
                lastTerm = entry.term();
                proposals.completeApplied(entry.index(), entry.term(), response);
            }
        }
        backlog.addAndGet(-batch.size());
        if (lastTerm > 0) {
            trigger.afterApply(appliedIndex, lastTerm, configuration, bytes);
        }
    }
}
