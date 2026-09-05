/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.server;

import dev.flotilla.core.NodeId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import org.jspecify.annotations.Nullable;

final class ProposalRegistry {

    private final ConcurrentNavigableMap<Long, Pending> pending = new ConcurrentSkipListMap<>();

    void register(long term, long index, CompletableFuture<Long> result) {
        Pending replaced = pending.put(index, new Pending(term, result));
        if (replaced != null) {
            replaced.result().completeExceptionally(new NotLeaderException(null));
        }
    }

    void completeApplied(long index, long term) {
        Pending waiting = pending.remove(index);
        if (waiting == null) {
            return;
        }
        if (waiting.term() == term) {
            waiting.result().complete(index);
        } else {
            waiting.result().completeExceptionally(new NotLeaderException(null));
        }
    }

    void failAll(@Nullable NodeId leader) {
        List<Pending> waiting = new ArrayList<>();
        for (Long index : pending.keySet()) {
            Pending removed = pending.remove(index);
            if (removed != null) {
                waiting.add(removed);
            }
        }
        waiting.forEach(entry -> entry.result().completeExceptionally(new NotLeaderException(leader)));
    }

    int size() {
        return pending.size();
    }

    private record Pending(long term, CompletableFuture<Long> result) {}
}
