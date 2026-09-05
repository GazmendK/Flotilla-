/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.server;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

public final class EventQueue {

    private final BlockingQueue<NodeEvent> queue;
    private final AtomicLong rejectedProposals = new AtomicLong();
    private final AtomicLong droppedTicks = new AtomicLong();

    public EventQueue(int capacity) {
        this.queue = new ArrayBlockingQueue<>(capacity);
    }

    public boolean offerProposal(NodeEvent event) {
        if (queue.offer(event)) {
            return true;
        }
        rejectedProposals.incrementAndGet();
        return false;
    }

    public void offerTick(NodeEvent event) {
        if (!queue.offer(event)) {
            droppedTicks.incrementAndGet();
        }
    }

    public boolean offerShutdown(NodeEvent event) {
        return queue.offer(event);
    }

    public boolean offerInbound(NodeEvent event) {
        return queue.offer(event);
    }

    public int drainTo(Collection<NodeEvent> target, int max) {
        return queue.drainTo(target, max);
    }

    public List<NodeEvent> drain() {
        List<NodeEvent> drained = new ArrayList<>();
        queue.drainTo(drained);
        return drained;
    }

    public NodeEvent take() throws InterruptedException {
        return queue.take();
    }

    public int depth() {
        return queue.size();
    }

    public int remainingCapacity() {
        return queue.remainingCapacity();
    }

    public long rejectedProposals() {
        return rejectedProposals.get();
    }

    public long droppedTicks() {
        return droppedTicks.get();
    }
}
