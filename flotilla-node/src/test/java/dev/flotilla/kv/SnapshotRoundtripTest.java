/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.kv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.flotilla.core.Bytes;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SnapshotRoundtripTest {

    private final KvStateMachine machine = new KvStateMachine();

    private long index;

    private KvResponse apply(KvRequest request) {
        return CommandCodec.decodeResponse(machine.apply(++index, CommandCodec.encode(request)));
    }

    private long openSession() {
        return ((KvResponse.Opened) apply(KvRequest.register())).clientId();
    }

    private void populate() {
        long first = openSession();
        long second = openSession();
        apply(KvRequest.of(first, 1, Command.put("alpha", "1")));
        apply(KvRequest.of(second, 1, Command.put("beta", "2")));
        apply(KvRequest.of(first, 2, Command.put("gamma", "3")));
        apply(KvRequest.anonymous(Command.delete("beta")));
    }

    @Test
    @DisplayName("data, sessions and the applied index all survive a round trip")
    void everythingInTheSnapshotComesBack() {
        populate();

        KvStateMachine restored = new KvStateMachine();
        restored.restore(machine.snapshot());

        assertThat(restored.entries()).isEqualTo(machine.entries());
        assertThat(restored.openSessions()).isEqualTo(machine.openSessions());
        assertThat(restored.lastAppliedIndex())
                .as("an applied index left out means a restored node re-applies entries it already had")
                .isEqualTo(machine.lastAppliedIndex());
        assertThat(restored.snapshot()).isEqualTo(machine.snapshot());
    }

    @Test
    void anEmptyMachineRoundTrips() {
        KvStateMachine restored = new KvStateMachine();
        restored.restore(machine.snapshot());

        assertThat(restored.snapshot()).isEqualTo(machine.snapshot());
        assertThat(restored.size()).isZero();
        assertThat(restored.openSessions()).isZero();
    }

    @Test
    @DisplayName("restoring replaces the state instead of merging into it")
    void restoreDiscardsWhateverWasThere() {
        populate();
        Bytes snapshot = machine.snapshot();

        KvStateMachine other = new KvStateMachine();
        other.apply(1, CommandCodec.encode(KvRequest.anonymous(Command.put("stale", "leftover"))));
        other.restore(snapshot);

        assertThat(other.get(Bytes.ofUtf8("stale"))).isEmpty();
        assertThat(other.snapshot()).isEqualTo(snapshot);
    }

    @Test
    @DisplayName("a truncated snapshot is reported, never half-restored")
    void aTruncatedSnapshotIsRefused() {
        populate();
        byte[] snapshot = machine.snapshot().toByteArray();

        for (int length = 0; length < snapshot.length; length++) {
            byte[] truncated = Arrays.copyOf(snapshot, length);
            assertThatThrownBy(() -> KvSnapshotCodec.decode(Bytes.wrap(truncated)))
                    .as("truncated to %d of %d bytes", length, snapshot.length)
                    .isInstanceOf(MalformedCommandException.class);
        }
    }

    @Test
    void anOlderFormatVersionIsRefusedRatherThanGuessed() {
        byte[] snapshot = machine.snapshot().toByteArray();
        snapshot[0] = 1;

        assertThatThrownBy(() -> KvSnapshotCodec.decode(Bytes.wrap(snapshot)))
                .isInstanceOf(MalformedCommandException.class)
                .hasMessageContaining("format version");
    }
}
