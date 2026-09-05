/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.server;

import static org.assertj.core.api.Assertions.assertThat;

import dev.flotilla.core.Bytes;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EventQueueTest {

    private static NodeEvent proposal() {
        return new NodeEvent.Proposal(Bytes.ofUtf8("x"), new CompletableFuture<>());
    }

    @Test
    @DisplayName("a full queue rejects proposals instead of growing")
    void aFullQueueRejectsProposals() {
        EventQueue queue = new EventQueue(2);

        assertThat(queue.offerProposal(proposal())).isTrue();
        assertThat(queue.offerProposal(proposal())).isTrue();
        assertThat(queue.offerProposal(proposal())).isFalse();

        assertThat(queue.depth()).isEqualTo(2);
        assertThat(queue.rejectedProposals()).isEqualTo(1);
    }

    @Test
    @DisplayName("a dropped tick is counted, not fatal, because the next one arrives anyway")
    void ticksAreDroppedSilentlyButCounted() {
        EventQueue queue = new EventQueue(1);
        queue.offerTick(new NodeEvent.Tick());

        queue.offerTick(new NodeEvent.Tick());

        assertThat(queue.droppedTicks()).isEqualTo(1);
        assertThat(queue.depth()).isEqualTo(1);
    }

    @Test
    void takeReturnsEventsInOrder() throws Exception {
        EventQueue queue = new EventQueue(4);
        NodeEvent first = proposal();
        NodeEvent second = new NodeEvent.Tick();
        queue.offerProposal(first);
        queue.offerTick(second);

        assertThat(queue.take()).isSameAs(first);
        assertThat(queue.take()).isSameAs(second);
    }

    @Test
    void capacityIsReportedHonestly() {
        EventQueue queue = new EventQueue(3);
        queue.offerProposal(proposal());

        assertThat(queue.remainingCapacity()).isEqualTo(2);
        assertThat(queue.depth()).isEqualTo(1);
    }
}
