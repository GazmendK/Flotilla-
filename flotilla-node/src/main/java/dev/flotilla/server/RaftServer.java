/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.server;

import dev.flotilla.core.Bytes;
import dev.flotilla.core.ClusterConfig;
import dev.flotilla.core.HardState;
import dev.flotilla.core.NodeId;
import dev.flotilla.core.RaftConfig;
import dev.flotilla.core.RaftNode;
import dev.flotilla.core.message.RaftMessage;
import dev.flotilla.core.port.RandomSource;
import dev.flotilla.kv.StateMachine;
import dev.flotilla.storage.FileStableStore;
import dev.flotilla.storage.SegmentedLogStore;
import dev.flotilla.storage.StorageConfig;
import dev.flotilla.storage.StorageDirectory;
import dev.flotilla.storage.io.RealFileIo;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RaftServer implements AutoCloseable {

    private static final long LEADERSHIP_POLL_MILLIS = 5;

    private final NodeId id;
    private final ServerConfig config;
    private final EventQueue events;
    private final RaftEngine engine;
    private final Thread engineThread;
    private final ScheduledExecutorService ticker;
    private final ScheduledFuture<?> tickerTask;
    private final SegmentedLogStore log;
    private final FileStableStore stable;
    private final AtomicBoolean closed = new AtomicBoolean();

    private RaftServer(
            NodeId id,
            ServerConfig config,
            EventQueue events,
            RaftEngine engine,
            Thread engineThread,
            ScheduledExecutorService ticker,
            ScheduledFuture<?> tickerTask,
            SegmentedLogStore log,
            FileStableStore stable) {
        this.id = id;
        this.config = config;
        this.events = events;
        this.engine = engine;
        this.engineThread = engineThread;
        this.ticker = ticker;
        this.tickerTask = tickerTask;
        this.log = log;
        this.stable = stable;
    }

    public static RaftServer start(
            ServerConfig serverConfig,
            RaftConfig raftConfig,
            ClusterConfig cluster,
            StorageConfig storageConfig,
            StateMachine stateMachine,
            MessageSink sink) {
        Objects.requireNonNull(serverConfig, "serverConfig");
        Objects.requireNonNull(raftConfig, "raftConfig");
        Objects.requireNonNull(cluster, "cluster");
        Objects.requireNonNull(storageConfig, "storageConfig");
        Objects.requireNonNull(stateMachine, "stateMachine");
        Objects.requireNonNull(sink, "sink");

        StorageDirectory directory = StorageDirectory.open(new RealFileIo(), storageConfig.directory());
        SegmentedLogStore log = SegmentedLogStore.open(directory, storageConfig);
        FileStableStore stable = FileStableStore.open(directory, storageConfig);
        HardState persisted = stable.load().orElse(HardState.INITIAL);

        NodeId id = raftConfig.nodeId();
        long seed = System.nanoTime() ^ ((long) id.value().hashCode() << 32);
        RaftNode raft = new RaftNode(raftConfig, cluster, log, RandomSource.seeded(seed), persisted);

        EventQueue events = new EventQueue(serverConfig.eventQueueCapacity());
        RaftEngine engine = new RaftEngine(raft, log, stable, stateMachine, sink, events);
        engine.replayThrough(persisted.commitIndex());

        Thread engineThread = new Thread(engine, "flotilla-" + id + "-eventloop");
        engineThread.start();

        ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "flotilla-" + id + "-ticker");
            thread.setDaemon(true);
            return thread;
        });
        long tickMillis = Math.max(1, serverConfig.tickInterval().toMillis());
        ScheduledFuture<?> tickerTask = ticker.scheduleWithFixedDelay(
                () -> events.offerTick(new NodeEvent.Tick()), tickMillis, tickMillis, TimeUnit.MILLISECONDS);

        return new RaftServer(id, serverConfig, events, engine, engineThread, ticker, tickerTask, log, stable);
    }

    public NodeId id() {
        return id;
    }

    public boolean isLeader() {
        return engine.isLeader();
    }

    public long currentTerm() {
        return engine.currentTerm();
    }

    public long commitIndex() {
        return engine.commitIndex();
    }

    public long appliedIndex() {
        return engine.appliedIndex();
    }

    public int pendingProposals() {
        return engine.pendingProposals();
    }

    public EventQueue events() {
        return events;
    }

    public Optional<RuntimeException> failure() {
        return engine.failure();
    }

    public CompletableFuture<Long> propose(Bytes command) {
        Objects.requireNonNull(command, "command");
        CompletableFuture<Long> result = new CompletableFuture<>();
        if (closed.get() || !engine.isRunning()) {
            result.completeExceptionally(new IllegalStateException("Server " + id + " is not running"));
            return result;
        }
        if (!events.offerProposal(new NodeEvent.Proposal(command, result))) {
            result.completeExceptionally(new BackpressureException("Event queue of " + id + " is full (capacity "
                    + config.eventQueueCapacity() + "); the proposal was rejected rather than buffered"));
        }
        return result;
    }

    public void deliver(RaftMessage message) {
        Objects.requireNonNull(message, "message");
        if (!closed.get()) {
            events.offerInbound(new NodeEvent.Inbound(message));
        }
    }

    public boolean awaitLeadership(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (engine.isLeader()) {
                return true;
            }
            if (!engine.isRunning()) {
                return false;
            }
            try {
                Thread.sleep(LEADERSHIP_POLL_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return engine.isLeader();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        tickerTask.cancel(false);
        ticker.shutdownNow();
        engine.stop();
        engineThread.interrupt();
        try {
            engineThread.join(config.shutdownTimeout().toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        RaftEngine.failQueuedProposals(events);
        stable.close();
        log.close();
    }
}
