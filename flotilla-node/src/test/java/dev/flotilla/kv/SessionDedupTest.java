/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.kv;

import static org.assertj.core.api.Assertions.assertThat;

import dev.flotilla.core.Bytes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SessionDedupTest {

    private final KvStateMachine machine = new KvStateMachine();

    private long index;

    private static Bytes b(String text) {
        return Bytes.ofUtf8(text);
    }

    private KvResponse apply(KvRequest request) {
        return CommandCodec.decodeResponse(machine.apply(++index, CommandCodec.encode(request)));
    }

    private long openSession() {
        KvResponse response = apply(KvRequest.register());
        assertThat(response).isInstanceOf(KvResponse.Opened.class);
        return ((KvResponse.Opened) response).clientId();
    }

    @Test
    @DisplayName("a client id is the log index of its registration, so every replica derives the same one")
    void theClientIdComesFromTheLog() {
        long first = openSession();
        long second = openSession();

        assertThat(first).isEqualTo(1);
        assertThat(second).isEqualTo(2);
        assertThat(machine.openSessions()).isEqualTo(2);
    }

    @Test
    @DisplayName("a retried write is executed once and answered twice with the same response")
    void aRetriedWriteRunsOnlyOnce() {
        long client = openSession();

        KvResponse first = apply(KvRequest.of(client, 1, Command.put("k", "v")));
        KvResponse retry = apply(KvRequest.of(client, 1, Command.put("k", "v")));

        assertThat(first).isEqualTo(KvResponse.ABSENT);
        assertThat(retry).isEqualTo(first);
        assertThat(machine.get(b("k"))).contains(b("v"));
    }

    @Test
    @DisplayName("the case that makes sessions necessary: a retried compare-and-swap must not report failure")
    void aRetriedCompareAndSwapKeepsItsAnswer() {
        long client = openSession();
        KvRequest take = KvRequest.of(client, 1, new Command.CompareAndSwap(b("lock"), null, b("owner")));

        KvResponse first = apply(take);
        KvResponse retry = apply(take);

        assertThat(first).isEqualTo(new KvResponse.Swapped(true));
        assertThat(retry)
                .as("re-executing would see the lock already taken and wrongly tell the holder it lost the race")
                .isEqualTo(new KvResponse.Swapped(true));
    }

    @Test
    @DisplayName("without a session the same retry does execute twice, which is what at-least-once means")
    void anonymousRequestsAreNotDeduplicated() {
        KvRequest take = KvRequest.anonymous(new Command.CompareAndSwap(b("lock"), null, b("owner")));

        assertThat(apply(take)).isEqualTo(new KvResponse.Swapped(true));
        assertThat(apply(take))
                .as("this is precisely the defect that client sessions exist to remove")
                .isEqualTo(new KvResponse.Swapped(false));
    }

    @Test
    void anAdvancingSequenceExecutesEveryRequest() {
        long client = openSession();

        assertThat(apply(KvRequest.of(client, 1, Command.put("k", "one")))).isEqualTo(KvResponse.ABSENT);
        assertThat(apply(KvRequest.of(client, 2, Command.put("k", "two")))).isEqualTo(new KvResponse.Value(b("one")));
        assertThat(machine.get(b("k"))).contains(b("two"));
    }

    @Test
    @DisplayName("a sequence the cache has moved past is refused rather than re-executed")
    void aStaleSequenceIsRefused() {
        long client = openSession();
        apply(KvRequest.of(client, 1, Command.put("k", "one")));
        apply(KvRequest.of(client, 2, Command.put("k", "two")));

        assertThat(apply(KvRequest.of(client, 1, Command.put("k", "one"))))
                .isEqualTo(new KvResponse.Rejected(KvResponse.Reason.STALE_SEQUENCE));
        assertThat(machine.get(b("k")))
                .as("a stale retry must never resurrect an old write")
                .contains(b("two"));
    }

    @Test
    void anUnknownSessionIsRefused() {
        assertThat(apply(KvRequest.of(999, 1, Command.put("k", "v"))))
                .isEqualTo(new KvResponse.Rejected(KvResponse.Reason.UNKNOWN_SESSION));
        assertThat(machine.size()).isZero();
    }

    @Test
    @DisplayName("deduplication survives a snapshot, because the session table is part of it")
    void dedupSurvivesSnapshotAndRestore() {
        long client = openSession();
        KvRequest write = KvRequest.of(client, 1, Command.put("k", "v"));
        KvResponse first = apply(write);

        KvStateMachine restored = new KvStateMachine();
        restored.restore(machine.snapshot());

        KvResponse retry = CommandCodec.decodeResponse(
                restored.apply(restored.lastAppliedIndex() + 1, CommandCodec.encode(write)));

        assertThat(retry)
                .as("a session table left out of the snapshot makes a restored node re-run old requests")
                .isEqualTo(first);
        assertThat(restored.openSessions()).isEqualTo(1);
    }

    @Test
    @DisplayName("sessions expire by log index, never by a clock, so all replicas expire the same one")
    void sessionsExpireOnTheLogNotTheClock() {
        KvStateMachine shortLived = new KvStateMachine(10);
        long client = ((KvResponse.Opened)
                        CommandCodec.decodeResponse(shortLived.apply(1, CommandCodec.encode(KvRequest.register()))))
                .clientId();

        for (long i = 2; i <= 11; i++) {
            shortLived.apply(i, CommandCodec.encode(KvRequest.anonymous(Command.put("filler", "x"))));
        }
        assertThat(shortLived.openSessions()).isEqualTo(1);

        shortLived.apply(12, CommandCodec.encode(KvRequest.anonymous(Command.put("filler", "x"))));

        assertThat(shortLived.openSessions()).isZero();
        assertThat(shortLived.expiredSessions()).isEqualTo(1);
        assertThat(CommandCodec.decodeResponse(
                        shortLived.apply(13, CommandCodec.encode(KvRequest.of(client, 1, Command.put("k", "v"))))))
                .isEqualTo(new KvResponse.Rejected(KvResponse.Reason.UNKNOWN_SESSION));
    }

    @Test
    @DisplayName("a retry keeps its session alive rather than letting it time out mid-conversation")
    void aRetryRefreshesTheSession() {
        KvStateMachine shortLived = new KvStateMachine(5);
        long client = ((KvResponse.Opened)
                        CommandCodec.decodeResponse(shortLived.apply(1, CommandCodec.encode(KvRequest.register()))))
                .clientId();
        KvRequest write = KvRequest.of(client, 1, Command.put("k", "v"));
        shortLived.apply(2, CommandCodec.encode(write));

        for (long i = 3; i <= 20; i += 4) {
            assertThat(CommandCodec.decodeResponse(shortLived.apply(i, CommandCodec.encode(write))))
                    .as("retry at index %d", i)
                    .isEqualTo(KvResponse.ABSENT);
        }

        assertThat(shortLived.openSessions()).isEqualTo(1);
        assertThat(shortLived.expiredSessions()).isZero();
    }
}
