/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.sim;

import dev.flotilla.core.Bytes;
import dev.flotilla.core.NodeId;
import dev.flotilla.linearizability.History;
import dev.flotilla.linearizability.KvModel;
import dev.flotilla.linearizability.KvModel.CompareAndSwap;
import dev.flotilla.linearizability.KvModel.Get;
import dev.flotilla.linearizability.KvModel.Input;
import dev.flotilla.linearizability.KvModel.Output;
import dev.flotilla.linearizability.KvModel.Put;
import dev.flotilla.linearizability.Operation;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

public final class SimWorkload {

    private sealed interface Pending {
        int operation();

        NodeId node();

        int restarts();
    }

    private record PendingWrite(
            int operation, NodeId node, int restarts, long deadline, long index, String id, Input input)
            implements Pending {}

    private record PendingRead(
            int operation,
            NodeId node,
            int restarts,
            long deadline,
            Bytes requestId,
            String key,
            @Nullable Long readIndex)
            implements Pending {}

    private final WorkloadConfig config;
    private final DeterministicRandom random;
    private final History<Input, Output> history = new History<>();
    private final List<@Nullable Pending> pending;
    private final Map<String, String> lastWritten = new HashMap<>();

    private long sequence;
    private long eventsThisStep;
    private boolean starting = true;
    private long completed;
    private long indeterminate;
    private long failed;

    public SimWorkload(WorkloadConfig config, long seed) {
        this.config = Objects.requireNonNull(config, "config");
        this.random = new DeterministicRandom(seed * 7919 + 104_729);
        this.pending = new ArrayList<>();
        for (int client = 0; client < config.clients(); client++) {
            pending.add(null);
        }
    }

    public List<Operation<Input, Output>> history() {
        return history.operations();
    }

    public long completed() {
        return completed;
    }

    public long indeterminate() {
        return indeterminate;
    }

    public long failed() {
        return failed;
    }

    void stopStarting() {
        starting = false;
    }

    void resumeStarting() {
        starting = true;
    }

    void step(Collection<SimNode> nodes, long now, long tick, Consumer<SimNode> drain) {
        eventsThisStep = 0;
        for (int client = 0; client < pending.size(); client++) {
            Pending current = pending.get(client);
            if (current != null) {
                pending.set(client, advance(current, nodes, now, tick));
            } else if (starting && random.chance(config.operationProbability())) {
                pending.set(client, start(client, nodes, now, tick, drain));
            }
        }
    }

    private @Nullable Pending advance(Pending current, Collection<SimNode> nodes, long now, long tick) {
        SimNode node = find(nodes, current.node());
        if (node.restarts() != current.restarts()) {
            return giveUp(current, now);
        }
        return switch (current) {
            case PendingWrite write -> advanceWrite(write, node, now, tick);
            case PendingRead read -> advanceRead(read, node, now, tick);
        };
    }

    private @Nullable Pending advanceWrite(PendingWrite write, SimNode node, long now, long tick) {
        Optional<SimStateMachine.Applied> applied = node.stateMachine().takeApplied(write.index());
        if (applied.isPresent()) {
            if (applied.get().operation().equals(write.id())) {
                history.ok(
                        write.operation(), output(write.input(), applied.get().response()), stamp(now));
                completed++;
            } else {
                history.fail(write.operation(), stamp(now));
                failed++;
            }
            return null;
        }
        if (node.restoredFromSnapshotAt() >= write.index() || tick >= write.deadline()) {
            return giveUp(write, now);
        }
        return write;
    }

    private @Nullable Pending advanceRead(PendingRead read, SimNode node, long now, long tick) {
        Long readIndex = read.readIndex();
        if (readIndex == null) {
            readIndex = node.takeReadIndex(read.requestId()).orElse(null);
        }
        if (readIndex != null && node.stateMachine().lastApplied() >= readIndex) {
            history.ok(
                    read.operation(),
                    new KvModel.Value(node.stateMachine().get(read.key()).orElse(null)),
                    stamp(now));
            completed++;
            return null;
        }
        if (tick >= read.deadline()) {
            return giveUp(read, now);
        }
        return new PendingRead(
                read.operation(),
                read.node(),
                read.restarts(),
                read.deadline(),
                read.requestId(),
                read.key(),
                readIndex);
    }

    private long stamp(long now) {
        eventsThisStep++;
        if (eventsThisStep >= VirtualClock.UNITS_PER_TICK) {
            throw new IllegalStateException("more client events in one step than a tick has time units");
        }
        return now + eventsThisStep;
    }

    private @Nullable Pending giveUp(Pending current, long now) {
        history.info(current.operation(), stamp(now));
        indeterminate++;
        return null;
    }

    private @Nullable Pending start(
            int client, Collection<SimNode> nodes, long now, long tick, Consumer<SimNode> drain) {
        String key = "k" + random.nextInt(config.keys());
        sequence++;
        String id = "c" + client + "-" + sequence;
        long deadline = tick + config.timeoutTicks();
        int kind = random.nextInt(10);
        return kind < 5
                ? startRead(client, key, id, nodes, now, deadline, drain)
                : startWrite(client, key, id, kind < 8, nodes, now, deadline, drain);
    }

    private @Nullable Pending startWrite(
            int client,
            String key,
            String id,
            boolean plainPut,
            Collection<SimNode> nodes,
            long now,
            long deadline,
            Consumer<SimNode> drain) {
        List<SimNode> leaders = new ArrayList<>();
        for (SimNode node : nodes) {
            if (node.isRunning() && node.raft().isLeader()) {
                leaders.add(node);
            }
        }
        if (leaders.isEmpty()) {
            return null;
        }
        SimNode leader = random.pick(leaders);
        Input input;
        Bytes command;
        if (plainPut) {
            String value = id;
            lastWritten.put(key, value);
            input = new Put(key, value);
            command = SimStateMachine.put(id, key, value);
        } else {
            String expected = random.nextInt(4) == 0 ? null : lastWritten.get(key);
            String value = random.nextInt(4) == 0 ? null : id;
            if (value != null) {
                lastWritten.put(key, value);
            }
            input = new CompareAndSwap(key, expected, value);
            command = SimStateMachine.compareAndSwap(id, key, expected, value);
        }
        long index = leader.raft().lastLogIndex() + 1;
        leader.stateMachine().watch(index);
        int operation = history.invoke(client, input, stamp(now));
        leader.raft().propose(command);
        drain.accept(leader);
        return new PendingWrite(operation, leader.id(), leader.restarts(), deadline, index, id, input);
    }

    private @Nullable Pending startRead(
            int client,
            String key,
            String id,
            Collection<SimNode> nodes,
            long now,
            long deadline,
            Consumer<SimNode> drain) {
        List<SimNode> running = new ArrayList<>();
        for (SimNode node : nodes) {
            if (node.isRunning()) {
                running.add(node);
            }
        }
        if (running.isEmpty()) {
            return null;
        }
        SimNode node = random.pick(running);
        int operation = history.invoke(client, new Get(key), stamp(now));
        if (config.readMode() == WorkloadConfig.ReadMode.UNSAFE_LOCAL) {
            history.ok(operation, new KvModel.Value(node.stateMachine().get(key).orElse(null)), stamp(now));
            completed++;
            return null;
        }
        Bytes requestId = Bytes.ofUtf8("read-" + id);
        boolean accepted = config.readMode() == WorkloadConfig.ReadMode.LEASE
                ? node.raft().leaseRead(requestId)
                : node.raft().readIndex(requestId);
        if (!accepted) {
            history.fail(operation, stamp(now));
            failed++;
            return null;
        }
        drain.accept(node);
        return new PendingRead(operation, node.id(), node.restarts(), deadline, requestId, key, null);
    }

    private static Output output(Input input, String response) {
        return input instanceof CompareAndSwap
                ? new KvModel.Swapped(Boolean.parseBoolean(response))
                : new KvModel.Value(SimStateMachine.fromNil(response));
    }

    private static SimNode find(Collection<SimNode> nodes, NodeId id) {
        for (SimNode node : nodes) {
            if (node.id().equals(id)) {
                return node;
            }
        }
        throw new IllegalStateException("Unknown node " + id);
    }
}
