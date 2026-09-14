/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.server;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.flotilla.core.NodeId;
import dev.flotilla.core.RaftConfig;
import dev.flotilla.transport.TransportConfig;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FlotillaNodeTimingTest {

    private static final RaftConfig RAFT = RaftConfig.defaults(NodeId.of("n1"));

    @Test
    void theDefaultsAreConsistentWithEachOther() {
        assertThatCode(() -> FlotillaNode.validateTiming(ServerConfig.defaults(), RAFT, TransportConfig.defaults()))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a delivery deadline that outlasts an election timeout is refused at startup")
    void aDeadlineLongerThanAnElectionIsRefused() {
        ServerConfig server = ServerConfig.defaults().withTickInterval(Duration.ofMillis(10));
        TransportConfig transport = TransportConfig.defaults().withDeliverDeadline(Duration.ofMillis(100));

        assertThatThrownBy(() -> FlotillaNode.validateTiming(server, RAFT, transport))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("minimum election timeout")
                .hasMessageContaining("10 ticks");
    }

    @Test
    @DisplayName("a message limit smaller than the largest batch the leader may send is refused at startup")
    void aMessageLimitBelowTheBatchSizeIsRefused() {
        TransportConfig transport = TransportConfig.defaults().withMaxMessageBytes(1024 * 1024);

        assertThatThrownBy(() -> FlotillaNode.validateTiming(ServerConfig.defaults(), RAFT, transport))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxAppendBytes")
                .hasMessageContaining("never catch up");
    }
}
