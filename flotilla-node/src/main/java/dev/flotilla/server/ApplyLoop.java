/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.server;

import dev.flotilla.core.EntryType;
import dev.flotilla.core.LogEntry;
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
    private final BlockingQueue<List<LogEntry>> pending;
    private final AtomicLong backlog = new AtomicLong();

    private volatile boolean running = true;
    private volatile long appliedIndex;

    @Nullable
    private volatile RuntimeException failure;

    ApplyLoop(StateMachine stateMachine, ProposalRegistry proposals, int capacity) {
        this.stateMachine = Objects.requireNonNull(stateMachine, "stateMachine");
        this.proposals = Objects.requireNonNull(proposals, "proposals");
        this.pending = new ArrayBlockingQueue<>(capacity);
    }

    void replayThrough(LogStore log, long throughIndex) {
        for (long index = Math.max(1, log.firstIndex()); index <= throughIndex; index++) {
            Optional<LogEntry> entry = log.entryAt(index);
            if (entry.isPresent() && entry.get().type() == EntryType.NORMAL) {
                stateMachine.apply(index, entry.get().data());
            }
        }
        appliedIndex = Math.max(appliedIndex, throughIndex);
    }

    void submit(List<LogEntry> committed) throws InterruptedException {
        if (committed.isEmpty()) {
            return;
        }
        backlog.addAndGet(committed.size());
        pending.put(committed);
    }

    @Override
    public void run() {
        try {
            while (running || !pending.isEmpty()) {
                List<LogEntry> batch = pending.poll(DRAIN_POLL_MILLIS, TimeUnit.MILLISECONDS);
                if (batch != null) {
                    applyBatch(batch);
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

    Optional<RuntimeException> failure() {
        return Optional.ofNullable(failure);
    }

    private void applyBatch(List<LogEntry> batch) {
        for (LogEntry entry : batch) {
            if (entry.index() > appliedIndex) {
                if (entry.type() == EntryType.NORMAL) {
                    stateMachine.apply(entry.index(), entry.data());
                }
                appliedIndex = entry.index();
                proposals.completeApplied(entry.index(), entry.term());
            }
        }
        backlog.addAndGet(-batch.size());
    }
}
