/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.transport;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TransportConfigTest {

    @Test
    @DisplayName("a chunk that cannot fit in a message alongside its framing is refused at startup")
    void anOversizedSnapshotChunkIsRefused() {
        assertThatThrownBy(() ->
                        TransportConfig.defaults().withSnapshotChunkBytes(TransportConfig.DEFAULT_MAX_MESSAGE_BYTES))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("half of maxMessageBytes");
    }

    @Test
    @DisplayName("a snapshot limit below one chunk would refuse every transfer, so it is refused instead")
    void aSnapshotLimitBelowOneChunkIsRefused() {
        assertThatThrownBy(() -> TransportConfig.defaults()
                        .withSnapshotChunkBytes(1024 * 1024)
                        .withMaxSnapshotBytes(1024))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be at least snapshotChunkBytes");
    }

    @Test
    @DisplayName("the chunk deadline may outlast the delivery deadline, because a chunk holds no append slot")
    void theChunkDeadlineMayBeLongerThanTheDeliveryDeadline() {
        assertThatCode(() -> TransportConfig.defaults()
                        .withDeliverDeadline(Duration.ofMillis(100))
                        .withSnapshotChunkDeadline(Duration.ofMinutes(5)))
                .doesNotThrowAnyException();
    }
}
