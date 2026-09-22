/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.server;

import dev.flotilla.core.ClusterConfig;
import dev.flotilla.core.Snapshot;
import dev.flotilla.kv.StateCapture;
import dev.flotilla.kv.StateMachine;
import dev.flotilla.storage.WritableSnapshotStore;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

final class SnapshotManager implements WritableSnapshotStore, AutoCloseable {

    private static final System.Logger LOG = System.getLogger(SnapshotManager.class.getName());

    private final StateMachine stateMachine;
    private final WritableSnapshotStore files;
    private final EventQueue events;
    private final SnapshotPolicy policy;
    private final ExecutorService worker;

    private final AtomicBoolean inProgress = new AtomicBoolean();
    private final AtomicLong covered = new AtomicLong();
    private final AtomicLong taken = new AtomicLong();
    private final AtomicLong refused = new AtomicLong();
    private final AtomicLong longestPauseNanos = new AtomicLong();
    private final AtomicLong totalPauseNanos = new AtomicLong();

    private long bytesSinceSnapshot;

    SnapshotManager(
            StateMachine stateMachine,
            WritableSnapshotStore files,
            EventQueue events,
            SnapshotPolicy policy,
            String nodeName) {
        this.stateMachine = Objects.requireNonNull(stateMachine, "stateMachine");
        this.files = Objects.requireNonNull(files, "files");
        this.events = Objects.requireNonNull(events, "events");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.worker = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "flotilla-" + nodeName + "-snapshot");
            thread.setDaemon(true);
            return thread;
        });
        files.latest().ifPresent(snapshot -> covered.set(snapshot.lastIncludedIndex()));
    }

    @Override
    public Optional<Snapshot> latest() {
        return files.latest();
    }

    @Override
    public void save(Snapshot snapshot) {
        files.save(snapshot);
        covered.accumulateAndGet(snapshot.lastIncludedIndex(), Math::max);
    }

    void afterApply(long appliedIndex, long appliedTerm, ClusterConfig configuration, long bytesApplied) {
        bytesSinceSnapshot += bytesApplied;
        if (!policy.isDue(appliedIndex, covered.get(), bytesSinceSnapshot)) {
            return;
        }
        if (!inProgress.compareAndSet(false, true)) {
            refused.incrementAndGet();
            return;
        }

        long start = System.nanoTime();
        StateCapture capture = stateMachine.capture();
        long pause = System.nanoTime() - start;
        longestPauseNanos.accumulateAndGet(pause, Math::max);
        totalPauseNanos.addAndGet(pause);
        bytesSinceSnapshot = 0;

        try {
            worker.execute(() -> writeAndCompact(capture, appliedIndex, appliedTerm, configuration));
        } catch (RejectedExecutionException shuttingDown) {
            capture.close();
            inProgress.set(false);
        }
    }

    private void writeAndCompact(StateCapture capture, long index, long term, ClusterConfig configuration) {
        try (capture) {
            if (index <= covered.get()) {
                return;
            }
            save(new Snapshot(index, term, configuration, capture.serialize()));
            taken.incrementAndGet();
            events.offerCompact(new NodeEvent.Compact(index));
        } catch (RuntimeException failure) {
            LOG.log(System.Logger.Level.WARNING, () -> "Snapshot through index " + index + " failed: " + failure);
        } finally {
            inProgress.set(false);
        }
    }

    long snapshotsTaken() {
        return taken.get();
    }

    long snapshotsSkippedBecauseOneWasRunning() {
        return refused.get();
    }

    Duration longestApplyPause() {
        return Duration.ofNanos(longestPauseNanos.get());
    }

    Duration totalApplyPause() {
        return Duration.ofNanos(totalPauseNanos.get());
    }

    @Override
    public void close() {
        worker.shutdown();
        try {
            if (!worker.awaitTermination(5, TimeUnit.SECONDS)) {
                worker.shutdownNow();
            }
        } catch (InterruptedException interrupted) {
            worker.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
