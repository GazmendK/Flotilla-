/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.server;

import static org.assertj.core.api.Assertions.assertThat;

import dev.flotilla.core.Bytes;
import dev.flotilla.core.NodeId;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ProposalRegistryTest {

    private final ProposalRegistry registry = new ProposalRegistry();

    @Test
    void anAppliedEntryCompletesItsProposalWithTheIndex() {
        CompletableFuture<Applied> result = new CompletableFuture<>();
        registry.register(3, 7, result);

        registry.completeApplied(7, 3, Bytes.ofUtf8("ok"));

        assertThat(result).isCompletedWithValue(new Applied(7, Bytes.ofUtf8("ok")));
        assertThat(registry.size()).isZero();
    }

    @Test
    @DisplayName("an index reused by a later term fails the proposal instead of reporting a phantom commit")
    void anIndexReusedByAnotherTermIsNotACommit() {
        CompletableFuture<Applied> result = new CompletableFuture<>();
        registry.register(3, 7, result);

        registry.completeApplied(7, 4, Bytes.EMPTY);

        assertThat(result).isCompletedExceptionally();
        assertThat(causeOf(result)).isInstanceOf(NotLeaderException.class);
    }

    @Test
    @DisplayName("a proposal overwritten at the same index is failed, never left dangling")
    void reregisteringAnIndexFailsTheDisplacedProposal() {
        CompletableFuture<Applied> displaced = new CompletableFuture<>();
        CompletableFuture<Applied> replacement = new CompletableFuture<>();
        registry.register(3, 7, displaced);

        registry.register(4, 7, replacement);

        assertThat(displaced).isCompletedExceptionally();
        assertThat(replacement).isNotDone();
        assertThat(registry.size()).isEqualTo(1);
    }

    @Test
    void applyingAnUnknownIndexIsHarmless() {
        registry.completeApplied(99, 1, Bytes.EMPTY);

        assertThat(registry.size()).isZero();
    }

    @Test
    @DisplayName("losing leadership fails every waiter and names the new leader")
    void failAllRedirectsToTheNewLeader() {
        CompletableFuture<Applied> first = new CompletableFuture<>();
        CompletableFuture<Applied> second = new CompletableFuture<>();
        registry.register(3, 7, first);
        registry.register(3, 8, second);

        registry.failAll(NodeId.of("n2"));

        assertThat(registry.size()).isZero();
        assertThat(causeOf(first))
                .isInstanceOfSatisfying(
                        NotLeaderException.class,
                        failure -> assertThat(failure.leader()).contains(NodeId.of("n2")));
        assertThat(second).isCompletedExceptionally();
    }

    private static Throwable causeOf(CompletableFuture<Applied> future) {
        try {
            future.join();
            return new AssertionError("expected the future to have failed");
        } catch (RuntimeException failure) {
            return failure.getCause() == null ? failure : failure.getCause();
        }
    }
}
