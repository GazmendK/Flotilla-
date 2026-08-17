/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.sim;

import dev.flotilla.core.Bytes;
import dev.flotilla.core.ClusterConfig;
import dev.flotilla.core.LogEntry;
import dev.flotilla.core.NodeId;
import dev.flotilla.core.RaftConfig;
import dev.flotilla.core.RaftNode;
import dev.flotilla.core.RaftRole;
import dev.flotilla.core.Ready;
import dev.flotilla.core.message.RaftMessage;
import dev.flotilla.sim.invariant.ElectionSafety;
import dev.flotilla.sim.invariant.Invariant;
import dev.flotilla.sim.invariant.InvariantViolation;
import dev.flotilla.sim.invariant.LeaderAppendOnly;
import dev.flotilla.sim.invariant.LeaderCompleteness;
import dev.flotilla.sim.invariant.LogMatching;
import dev.flotilla.sim.invariant.MonotonicProgress;
import dev.flotilla.sim.invariant.StateMachineSafety;
import dev.flotilla.sim.invariant.WorldView;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.PriorityQueue;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.stream.IntStream;

public final class Simulation implements WorldView {

    private static final int TRACE_CAPACITY = 400;

    private final long seed;
    private final SimConfig config;
    private final DeterministicRandom random;
    private final VirtualClock clock = new VirtualClock();
    private final VirtualNetwork network;
    private final TreeMap<NodeId, SimNode> nodes = new TreeMap<>();
    private final TreeMap<NodeId, List<LogEntry>> logSnapshots = new TreeMap<>();
    private final PriorityQueue<ScheduledMessage> inFlight = new PriorityQueue<>(ScheduledMessage.ORDER);
    private final List<Invariant> invariants;
    private final EventTrace trace = new EventTrace(TRACE_CAPACITY);

    private long sequence;
    private long proposalCounter;
    private boolean faultsEnabled = true;

    public Simulation(long seed, SimConfig config) {
        this.seed = seed;
        this.config = config;
        this.random = new DeterministicRandom(seed);

        List<NodeId> ids = IntStream.rangeClosed(1, config.voters())
                .mapToObj(index -> NodeId.of("n" + index))
                .toList();
        ClusterConfig cluster = ClusterConfig.ofVoters(ids);
        long nodeSeed = seed;
        for (NodeId id : ids) {
            nodeSeed = nodeSeed * 31 + 17;
            RaftConfig raftConfig = RaftConfig.builder(id)
                    .maxEntriesPerAppend(config.maxEntriesPerAppend())
                    .build();
            nodes.put(id, new SimNode(raftConfig, cluster, nodeSeed));
        }

        this.network = new VirtualNetwork(config, random, ids);
        this.invariants = List.of(
                new ElectionSafety(),
                new LeaderAppendOnly(),
                new LeaderCompleteness(),
                new StateMachineSafety(),
                new MonotonicProgress(),
                new LogMatching());
    }

    public static Simulation of(long seed) {
        return new Simulation(seed, SimConfig.chaotic(5));
    }

    public long seed() {
        return seed;
    }

    public EventTrace trace() {
        return trace;
    }

    public void run(int ticks) {
        for (int i = 0; i < ticks; i++) {
            step();
        }
    }

    public void runWithoutFaults(int ticks) {
        faultsEnabled = false;
        run(ticks);
        faultsEnabled = true;
    }

    public void step() {
        nodes.values().forEach(SimNode::beginStep);

        long target = clock.now() + VirtualClock.UNITS_PER_TICK;
        deliverUntil(target);
        clock.advanceTo(target);

        for (SimNode node : nodes.values()) {
            if (node.isRunning()) {
                node.raft().tick();
                drain(node);
            }
        }

        maybePropose();
        if (faultsEnabled) {
            injectFaults();
        }
        checkInvariants();
    }

    private void deliverUntil(long target) {
        while (!inFlight.isEmpty() && inFlight.peek().arrivalTime() <= target) {
            ScheduledMessage scheduled = inFlight.poll();
            clock.advanceTo(scheduled.arrivalTime());
            RaftMessage message = scheduled.message();

            SimNode recipient = nodes.get(message.to());
            if (recipient == null || !recipient.isRunning()) {
                continue;
            }
            if (!network.connected(message.from(), message.to())) {
                continue;
            }
            recipient.raft().step(message);
            drain(recipient);
        }
    }

    private void drain(SimNode node) {
        RaftNode raft = node.raft();
        Ready ready = raft.ready();
        if (ready.isEmpty()) {
            return;
        }

        ready.hardState().ifPresent(node.stable()::persist);
        if (!ready.entriesToPersist().isEmpty()) {
            node.log().syncThrough(ready.entriesToPersist().getLast().index());
        }

        ready.softState()
                .ifPresent(soft ->
                        trace.record(clock.now(), node.id() + " -> " + soft.role() + " term=" + raft.currentTerm()));

        node.recordApplied(ready.committedEntriesToApply());
        ready.messagesToSend().forEach(this::schedule);
        raft.advance();
    }

    private void schedule(RaftMessage message) {
        if (network.drops()) {
            return;
        }
        inFlight.add(new ScheduledMessage(clock.now() + network.latencyUnits(), sequence++, message));
        if (network.duplicates()) {
            inFlight.add(new ScheduledMessage(clock.now() + network.latencyUnits(), sequence++, message));
        }
    }

    private void maybePropose() {
        if (!random.chance(config.proposalProbability())) {
            return;
        }
        List<SimNode> leaders = new ArrayList<>();
        for (SimNode node : nodes.values()) {
            if (node.isRunning() && node.raft().isLeader()) {
                leaders.add(node);
            }
        }
        if (leaders.isEmpty()) {
            return;
        }
        SimNode leader = random.pick(leaders);
        proposalCounter++;
        leader.raft().propose(Bytes.ofUtf8("v" + proposalCounter));
        drain(leader);
    }

    private void injectFaults() {
        for (SimNode node : nodes.values()) {
            if (node.isRunning()) {
                if (random.chance(config.crashProbability())) {
                    node.crash();
                    trace.record(clock.now(), node.id() + " CRASH");
                }
            } else if (random.chance(config.restartProbability())) {
                node.restart();
                trace.record(clock.now(), node.id() + " RESTART");
            }
        }

        if (random.chance(config.repartitionProbability())) {
            network.repartition(nodes.keySet());
            trace.record(clock.now(), "PARTITION " + network.describe());
        } else if (network.isPartitioned() && random.chance(config.healProbability())) {
            network.heal(nodes.keySet());
            trace.record(clock.now(), "HEAL");
        }
    }

    private void checkInvariants() {
        logSnapshots.clear();
        nodes.forEach((id, node) -> logSnapshots.put(id, node.log().snapshotEntries()));

        boolean runExpensive = clock.tickNumber() % config.logMatchingCheckEveryTicks() == 0;
        for (Invariant invariant : invariants) {
            if (invariant.isExpensive() && !runExpensive) {
                continue;
            }
            try {
                invariant.observe(this);
            } catch (InvariantViolation violation) {
                throw new SimulationFailure(seed, violation, trace.render());
            }
        }
    }

    public void crash(NodeId id) {
        node(id).crash();
        trace.record(clock.now(), id + " CRASH");
    }

    public void restart(NodeId id) {
        node(id).restart();
        trace.record(clock.now(), id + " RESTART");
    }

    public void partition(Collection<NodeId> oneSide) {
        network.heal(nodes.keySet());
        oneSide.forEach(network::isolateTo);
        trace.record(clock.now(), "PARTITION " + network.describe());
    }

    public void heal() {
        network.heal(nodes.keySet());
        trace.record(clock.now(), "HEAL");
    }

    public void restartAll() {
        nodes.values().forEach(SimNode::restart);
        trace.record(clock.now(), "RESTART ALL");
    }

    public String digest() {
        StringBuilder builder = new StringBuilder();
        builder.append("t=").append(clock.now()).append('\n');
        nodes.forEach((id, node) -> {
            builder.append(id)
                    .append(" up=")
                    .append(node.isRunning())
                    .append(" restarts=")
                    .append(node.restarts())
                    .append(" term=")
                    .append(node.raft().currentTerm())
                    .append(" role=")
                    .append(node.raft().role())
                    .append(" commit=")
                    .append(node.raft().commitIndex())
                    .append(" log=");
            node.log()
                    .snapshotEntries()
                    .forEach(entry -> builder.append(entry.term())
                            .append('@')
                            .append(entry.index())
                            .append(' '));
            builder.append('\n');
        });
        return builder.toString();
    }

    public boolean propose(NodeId id, String value) {
        SimNode node = node(id);
        if (!node.isRunning()) {
            return false;
        }
        boolean accepted = node.raft().propose(Bytes.ofUtf8(value));
        drain(node);
        return accepted;
    }

    public SimNode node(NodeId id) {
        SimNode node = nodes.get(id);
        if (node == null) {
            throw new IllegalArgumentException("Unknown node " + id + " in " + nodes.keySet());
        }
        return node;
    }

    public List<NodeId> leaders() {
        List<NodeId> found = new ArrayList<>();
        nodes.forEach((id, node) -> {
            if (node.isRunning() && node.raft().isLeader()) {
                found.add(id);
            }
        });
        return List.copyOf(found);
    }

    public boolean hasLeader() {
        return !leaders().isEmpty();
    }

    public long highestCommitIndex() {
        return nodes.values().stream()
                .mapToLong(node -> node.raft().commitIndex())
                .max()
                .orElse(0);
    }

    public long appliedEntryCount() {
        return nodes.values().stream()
                .mapToLong(node -> node.appliedHistory().size())
                .sum();
    }

    @Override
    public long time() {
        return clock.now();
    }

    @Override
    public SortedSet<NodeId> nodes() {
        return nodes.navigableKeySet();
    }

    @Override
    public boolean isRunning(NodeId id) {
        return node(id).isRunning();
    }

    @Override
    public int restarts(NodeId id) {
        return node(id).restarts();
    }

    @Override
    public long term(NodeId id) {
        return node(id).raft().currentTerm();
    }

    @Override
    public RaftRole role(NodeId id) {
        return node(id).raft().role();
    }

    @Override
    public long commitIndex(NodeId id) {
        return node(id).raft().commitIndex();
    }

    @Override
    public List<LogEntry> log(NodeId id) {
        List<LogEntry> cached = logSnapshots.get(id);
        return cached == null ? node(id).log().snapshotEntries() : cached;
    }

    @Override
    public List<LogEntry> appliedThisStep(NodeId id) {
        return node(id).appliedThisStep();
    }
}
