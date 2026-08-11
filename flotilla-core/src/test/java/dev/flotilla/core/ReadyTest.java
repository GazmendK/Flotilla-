/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.flotilla.core.message.TimeoutNowRequest;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ReadyTest {
    private static final NodeId N1 = NodeId.of("n1");
    private static final NodeId N2 = NodeId.of("n2");

    @Test
    void anEmptyCycleIsRecognisedAsSuch() {
        assertThat(Ready.EMPTY.isEmpty()).isTrue();
        assertThat(Ready.EMPTY.requiresSync()).isFalse();
        assertThat(Ready.EMPTY.hardState()).isEmpty();
        assertThat(Ready.EMPTY.softState()).isEmpty();
    }

    @Test
    @DisplayName("a cycle that only sends messages needs no sync before they go out")
    void sendingAloneRequiresNoSync() {
        Ready ready = Ready.builder().send(new TimeoutNowRequest(N1, N2, 5)).build();

        assertThat(ready.isEmpty()).isFalse();
        assertThat(ready.requiresSync()).isFalse();
        assertThat(ready.messagesToSend()).hasSize(1);
    }

    @Test
    @DisplayName("new entries must be synced before this cycle's messages may be sent")
    void appendingEntriesRequiresASync() {
        Ready ready = Ready.builder().persist(LogEntry.noOp(1, 1)).build();

        assertThat(ready.requiresSync()).isTrue();
    }

    @Test
    @DisplayName("a changed vote or term must be synced before the vote response leaves the node")
    void changingHardStateRequiresASync() {
        Ready ready = Ready.builder().hardState(new HardState(2, N2, 0)).build();

        assertThat(ready.requiresSync()).isTrue();
        assertThat(ready.hardState()).map(HardState::currentTerm).contains(2L);
    }

    @Test
    @DisplayName("a role change alone is observable but needs no durability")
    void softStateChangeIsNotEmptyAndNeedsNoSync() {
        Ready ready =
                Ready.builder().softState(new SoftState(N1, RaftRole.LEADER)).build();

        assertThat(ready.isEmpty()).isFalse();
        assertThat(ready.requiresSync()).isFalse();
    }

    @Test
    @DisplayName("the runtime cannot mutate what the core handed it")
    void collectionsAreUnmodifiable() {
        Ready ready = Ready.builder().persist(LogEntry.noOp(1, 1)).build();

        assertThatThrownBy(() -> ready.entriesToPersist().add(LogEntry.noOp(1, 2)))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> ready.messagesToSend().add(new TimeoutNowRequest(N1, N2, 1)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("the builder copies, so later changes to it do not leak into a built Ready")
    void builtValuesAreDetachedFromTheBuilder() {
        Ready.Builder builder = Ready.builder().persist(LogEntry.noOp(1, 1));
        Ready first = builder.build();

        builder.persist(LogEntry.noOp(1, 2));

        assertThat(first.entriesToPersist()).hasSize(1);
        assertThat(builder.build().entriesToPersist()).hasSize(2);
    }

    @Test
    void committedEntriesAreCarriedInIndexOrder() {
        Ready ready = Ready.builder()
                .apply(List.of(LogEntry.noOp(1, 1), LogEntry.noOp(1, 2), LogEntry.noOp(1, 3)))
                .build();

        assertThat(ready.committedEntriesToApply()).extracting(LogEntry::index).containsExactly(1L, 2L, 3L);
    }
}
