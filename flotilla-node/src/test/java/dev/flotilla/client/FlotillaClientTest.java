/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.flotilla.core.Bytes;
import dev.flotilla.kv.KvStateMachine;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FlotillaClientTest {

    private static final InetSocketAddress A = FakeCluster.node(1);
    private static final InetSocketAddress B = FakeCluster.node(2);
    private static final InetSocketAddress C = FakeCluster.node(3);

    private static final ClientConfig CONFIG =
            ClientConfig.defaults().withMaxAttempts(5).withBackoff(Duration.ofMillis(10), Duration.ofMillis(80));

    private final List<Duration> sleeps = new ArrayList<>();
    private final AtomicLong clock = new AtomicLong();
    private final HistoryRecorder history = new HistoryRecorder(clock::incrementAndGet);

    private FlotillaClient client(FakeCluster cluster, List<InetSocketAddress> seeds) {
        return new FlotillaClient(cluster, seeds, CONFIG, new MaximalRandom(), sleeps::add, history);
    }

    private static Bytes b(String text) {
        return Bytes.ofUtf8(text);
    }

    private List<HistoryRecorder.Type> historyTypes() {
        return history.events().stream().map(HistoryRecorder.Event::type).toList();
    }

    @Test
    @DisplayName("a follower's hint sends the client straight to the leader, without waiting")
    void aFollowerRedirectsToTheLeader() {
        FakeCluster cluster = new FakeCluster(B, KvStateMachine.DEFAULT_SESSION_TIMEOUT_ENTRIES);

        try (FlotillaClient client = client(cluster, List.of(A, B, C))) {
            client.put(b("k"), b("v"));

            assertThat(client.get(b("k"))).contains(b("v"));
        }
        assertThat(cluster.targets).startsWith(A, B).containsOnly(A, B);
        assertThat(sleeps).isEmpty();
    }

    @Test
    @DisplayName("without a hint the client moves on to the next node and backs off")
    void withoutAHintTheClientRotates() {
        FakeCluster cluster = new FakeCluster(C, KvStateMachine.DEFAULT_SESSION_TIMEOUT_ENTRIES);
        cluster.leaderKnown = false;

        try (FlotillaClient client = client(cluster, List.of(A, B, C))) {
            client.put(b("k"), b("v"));
        }

        assertThat(cluster.targets).startsWith(A, B, C);
        assertThat(sleeps).hasSize(2);
    }

    @Test
    @DisplayName("a lost acknowledgement is retried with the same sequence, so the command runs once")
    void aLostAcknowledgementDoesNotRunTwice() {
        FakeCluster cluster = new FakeCluster(A, KvStateMachine.DEFAULT_SESSION_TIMEOUT_ENTRIES);

        try (FlotillaClient client = client(cluster, List.of(A))) {
            client.put(b("counter"), b("1"));
            cluster.loseAcknowledgementsNext = 1;

            boolean swapped = client.compareAndSwap(b("counter"), b("1"), b("2"));

            assertThat(swapped)
                    .as("the first attempt did swap; a re-execution would have seen 2 and reported failure")
                    .isTrue();
        }
        assertThat(cluster.store.get(b("counter"))).contains(b("2"));
        assertThat(historyTypes())
                .containsExactly(
                        HistoryRecorder.Type.INVOKE,
                        HistoryRecorder.Type.OK,
                        HistoryRecorder.Type.INVOKE,
                        HistoryRecorder.Type.OK);
    }

    @Test
    void overloadIsRetriedOnTheSameNodeAfterABackoff() {
        FakeCluster cluster = new FakeCluster(A, KvStateMachine.DEFAULT_SESSION_TIMEOUT_ENTRIES);

        try (FlotillaClient client = client(cluster, List.of(A, B))) {
            client.put(b("warm"), b("up"));
            cluster.overloadNext = 2;
            client.put(b("k"), b("v"));
        }

        assertThat(cluster.targets).containsOnly(A);
        assertThat(sleeps).hasSize(2);
    }

    @Test
    @DisplayName("an expired session on a request that cannot have run yet is renewed without the caller noticing")
    void anExpiredSessionIsRenewedWhenNothingCanHaveRun() {
        FakeCluster cluster = new FakeCluster(A, 3);

        try (FlotillaClient client = client(cluster, List.of(A))) {
            client.put(b("first"), b("1"));
            long firstSession = client.clientId();
            cluster.applyFiller(5);

            client.put(b("second"), b("2"));

            assertThat(client.clientId()).isNotEqualTo(firstSession);
        }
        assertThat(cluster.store.get(b("second"))).contains(b("2"));
    }

    @Test
    @DisplayName("an expired session after an attempt that may have run is reported, never silently retried")
    void anExpiredSessionAfterAnUncertainAttemptIsIndeterminate() {
        FakeCluster cluster = new FakeCluster(A, 3);

        try (FlotillaClient client = client(cluster, List.of(A))) {
            client.put(b("first"), b("1"));
            cluster.unavailableNext = 1;
            cluster.beforeCall.put(4, () -> cluster.applyFiller(5));

            assertThatThrownBy(() -> client.put(b("second"), b("2")))
                    .isInstanceOf(IndeterminateResultException.class)
                    .hasMessageContaining("may or may not have taken effect");
        }
        assertThat(historyTypes()).endsWith(HistoryRecorder.Type.INVOKE, HistoryRecorder.Type.INFO);
    }

    @Test
    @DisplayName("giving up is recorded as indeterminate, the only honest answer a client has")
    void givingUpIsIndeterminate() {
        FakeCluster cluster = new FakeCluster(A, KvStateMachine.DEFAULT_SESSION_TIMEOUT_ENTRIES);

        try (FlotillaClient client = client(cluster, List.of(A))) {
            client.put(b("warm"), b("up"));
            cluster.unavailableNext = 100;

            assertThatThrownBy(() -> client.put(b("k"), b("v"))).isInstanceOf(IndeterminateResultException.class);
        }
        assertThat(historyTypes()).endsWith(HistoryRecorder.Type.INVOKE, HistoryRecorder.Type.INFO);
        assertThat(sleeps).hasSize(CONFIG.maxAttempts());
    }

    @Test
    @DisplayName("backoff doubles from the base, stops at the cap, and is fully jittered below it")
    void backoffIsBoundedAndJittered() {
        RandomGenerator highest = new MaximalRandom();

        assertThat(FlotillaClient.backoff(CONFIG, highest, 0)).isEqualTo(Duration.ofMillis(10));
        assertThat(FlotillaClient.backoff(CONFIG, highest, 1)).isEqualTo(Duration.ofMillis(20));
        assertThat(FlotillaClient.backoff(CONFIG, highest, 3)).isEqualTo(Duration.ofMillis(80));
        assertThat(FlotillaClient.backoff(CONFIG, highest, 40)).isEqualTo(Duration.ofMillis(80));
        assertThat(FlotillaClient.backoff(CONFIG, new MinimalRandom(), 5)).isZero();
    }

    private static final class MaximalRandom implements RandomGenerator {
        @Override
        public long nextLong() {
            return Long.MAX_VALUE;
        }

        @Override
        public long nextLong(long bound) {
            return bound - 1;
        }
    }

    private static final class MinimalRandom implements RandomGenerator {
        @Override
        public long nextLong() {
            return 0;
        }

        @Override
        public long nextLong(long bound) {
            return 0;
        }
    }
}
