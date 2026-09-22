/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.client;

import dev.flotilla.core.Bytes;
import dev.flotilla.core.NodeId;
import dev.flotilla.kv.Command;
import dev.flotilla.kv.CommandCodec;
import dev.flotilla.kv.KvRequest;
import dev.flotilla.kv.KvStateMachine;
import dev.flotilla.transport.CallFailure;
import dev.flotilla.transport.ClientEndpoint;
import dev.flotilla.transport.Executed;
import dev.flotilla.transport.ReadConsistency;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

final class FakeCluster implements ClientEndpoint {

    static final NodeId LEADER_ID = NodeId.of("leader");

    final KvStateMachine store;
    final List<InetSocketAddress> targets = new ArrayList<>();
    final Map<Integer, Runnable> beforeCall = new HashMap<>();
    final Set<InetSocketAddress> down = new HashSet<>();

    InetSocketAddress leader;
    boolean leaderKnown = true;
    int unavailableNext;
    int overloadNext;
    int loseAcknowledgementsNext;

    private long index;

    FakeCluster(InetSocketAddress leader, long sessionTimeoutEntries) {
        this.leader = leader;
        this.store = new KvStateMachine(sessionTimeoutEntries);
    }

    static InetSocketAddress node(int port) {
        return InetSocketAddress.createUnresolved("node", port);
    }

    void applyFiller(int entries) {
        for (int i = 0; i < entries; i++) {
            store.apply(++index, CommandCodec.encode(KvRequest.anonymous(Command.put("filler", "x"))));
        }
    }

    @Override
    public CompletableFuture<Executed> query(
            InetSocketAddress target, Bytes query, ReadConsistency consistency, Duration deadline) {
        targets.add(target);
        if (down.contains(target) || unavailableNext > 0) {
            unavailableNext = Math.max(0, unavailableNext - (down.contains(target) ? 0 : 1));
            return CompletableFuture.failedFuture(CallFailure.of(CallFailure.Kind.UNAVAILABLE, "unreachable"));
        }
        if (!target.equals(leader) && consistency != ReadConsistency.STALE) {
            return CompletableFuture.failedFuture(
                    leaderKnown ? CallFailure.notLeader(LEADER_ID, leader) : CallFailure.notLeader(null, null));
        }
        return CompletableFuture.completedFuture(new Executed(index, store.query(query)));
    }

    @Override
    public CompletableFuture<Executed> execute(InetSocketAddress target, Bytes command, Duration deadline) {
        targets.add(target);
        Runnable hook = beforeCall.get(targets.size());
        if (hook != null) {
            hook.run();
        }
        if (down.contains(target)) {
            return CompletableFuture.failedFuture(CallFailure.of(CallFailure.Kind.UNAVAILABLE, "down"));
        }
        if (unavailableNext > 0) {
            unavailableNext--;
            return CompletableFuture.failedFuture(CallFailure.of(CallFailure.Kind.UNAVAILABLE, "unreachable"));
        }
        if (!target.equals(leader)) {
            return CompletableFuture.failedFuture(
                    leaderKnown ? CallFailure.notLeader(LEADER_ID, leader) : CallFailure.notLeader(null, null));
        }
        if (overloadNext > 0) {
            overloadNext--;
            return CompletableFuture.failedFuture(CallFailure.of(CallFailure.Kind.OVERLOADED, "busy"));
        }
        Bytes result = store.apply(++index, command);
        if (loseAcknowledgementsNext > 0) {
            loseAcknowledgementsNext--;
            return CompletableFuture.failedFuture(CallFailure.of(CallFailure.Kind.TIMED_OUT, "reply lost"));
        }
        return CompletableFuture.completedFuture(new Executed(index, result));
    }

    @Override
    public void close() {}
}
