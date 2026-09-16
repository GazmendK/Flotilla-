/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.server;

import dev.flotilla.core.Bytes;
import dev.flotilla.core.EntryType;
import dev.flotilla.core.LogEntry;
import dev.flotilla.core.Snapshot;
import dev.flotilla.core.port.LogStore;
import dev.flotilla.kv.StateMachine;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
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

    @Nullable
    private volatile RuntimeException failure;

    private record Work(@Nullable Snapshot snapshot, List<LogEntry> entries) {}

    ApplyLoop(StateMachine stateMachine, ProposalRegistry proposals, int capacity) {
        this.stateMachine = Objects.requireNonNull(stateMachine, "stateMachine");
        this.proposals = Objects.requireNonNull(proposals, "proposals");
        this.pending = new ArrayBlockingQueue<>(capacity);
    }

    void onApplied(SnapshotTrigger listener) {
        this.trigger = Objects.requireNonNull(listener, "listener");
    }

    void restoreFrom(Snapshot snapshot) {
        stateMachine.restore(snapshot.data());
        appliedIndex = snapshot.lastIncludedIndex();
        restores.incrementAndGet();
    }

    void replayThrough(LogStore log, long throughIndex) {
        for (long index = Math.max(appliedIndex + 1, log.firstIndex()); index <= throughIndex; index++) {
            Optional<LogEntry> entry = log.entryAt(index);
            if (entry.isPresent() && entry.get().type() == EntryType.NORMAL) {
                stateMachine.apply(index, entry.get().data());
            }
        }
        appliedIndex = Math.max(appliedIndex, throughIndex);
    }

    void submit(@Nullable Snapshot snapshot, List<LogEntry> committed) throws InterruptedException {
        if (snapshot == null && committed.isEmpty()) {
            return;
        }
        backlog.addAndGet(committed.size());
        pending.put(new Work(snapshot, committed));
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
        }
    }

    void stop() {
        running = false;
    }

    long appliedIndex() {
        return appliedIndex;
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
        Snapshot snapshot = work.snapshot();
        if (snapshot != null) {
            restoreFrom(snapshot);
        }
        applyBatch(work.entries());
    }

    private void applyBatch(List<LogEntry> batch) {
        long bytes = 0;
        long lastTerm = 0;
        for (LogEntry entry : batch) {
            if (entry.index() > appliedIndex) {
                Bytes response = entry.type() == EntryType.NORMAL
                        ? stateMachine.apply(entry.index(), entry.data())
                        : Bytes.EMPTY;
                appliedIndex = entry.index();
                bytes += entry.data().size();
                lastTerm = entry.term();
                proposals.completeApplied(entry.index(), entry.term(), response);
            }
        }
        backlog.addAndGet(-batch.size());
        if (lastTerm > 0) {
            trigger.afterApply(appliedIndex, lastTerm, bytes);
        }
    }
}
