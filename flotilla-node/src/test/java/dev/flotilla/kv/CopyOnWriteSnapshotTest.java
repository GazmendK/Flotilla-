/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.kv;

import static org.assertj.core.api.Assertions.assertThat;

import dev.flotilla.core.Bytes;
import java.time.Duration;
import java.util.Arrays;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CopyOnWriteSnapshotTest {

    private static final int KEYS = 200_000;
    private static final int VALUE_BYTES = 64;

    private static Bytes put(String key, String value) {
        return CommandCodec.encode(KvRequest.anonymous(new Command.Put(Bytes.ofUtf8(key), Bytes.ofUtf8(value))));
    }

    private static KvStateMachine filled(int keys) {
        KvStateMachine machine = new KvStateMachine();
        String value = "v".repeat(VALUE_BYTES);
        for (int i = 1; i <= keys; i++) {
            machine.apply(i, put("key-" + i, value));
        }
        return machine;
    }

    private static long nanosOf(Supplier<?> work) {
        long start = System.nanoTime();
        Object result = work.get();
        long elapsed = System.nanoTime() - start;
        assertThat(result).isNotNull();
        return elapsed;
    }

    @Test
    @DisplayName("a capture ignores everything applied after it, which is what makes it consistent")
    void aCaptureIsUnaffectedByLaterWrites() {
        KvStateMachine machine = new KvStateMachine();
        machine.apply(1, put("a", "first"));

        StateCapture capture = machine.capture();
        machine.apply(2, put("a", "second"));
        machine.apply(3, put("b", "late"));

        KvStateMachine restored = new KvStateMachine();
        restored.restore(capture.serialize());

        assertThat(restored.get(Bytes.ofUtf8("a")))
                .as("the snapshot has to describe the state at the index it claims, not a later one")
                .contains(Bytes.ofUtf8("first"));
        assertThat(restored.get(Bytes.ofUtf8("b"))).isEmpty();
        assertThat(restored.lastAppliedIndex()).isEqualTo(1);
    }

    private static long medianCaptureNanos(KvStateMachine machine) {
        long[] samples = new long[21];
        for (int sample = 0; sample < samples.length; sample++) {
            long start = System.nanoTime();
            StateCapture capture = machine.capture();
            samples[sample] = System.nanoTime() - start;
            capture.close();
            assertThat(machine.get(Bytes.ofUtf8("key-1"))).isPresent();
        }
        Arrays.sort(samples);
        return samples[samples.length / 2];
    }

    @Test
    @DisplayName("freezing the state costs the same for two hundred thousand keys as for a thousand")
    void aCaptureDoesNotGrowWithTheState() {
        long small = medianCaptureNanos(filled(1_000));
        KvStateMachine large = filled(KEYS);
        long frozen = medianCaptureNanos(large);
        long encodeNanos;
        try (StateCapture capture = large.capture()) {
            encodeNanos = nanosOf(capture::serialize);
        }

        System.out.println(
                "capture of 1000 keys: " + small + " ns, of " + KEYS + " keys: " + frozen + " ns, serialization of "
                        + KEYS + " keys: " + Duration.ofNanos(encodeNanos).toMillis() + " ms");
        assertThat(frozen)
                .as(
                        "the apply loop paused %d ns to freeze %d keys; anything that grows with the data would take "
                                + "tens of milliseconds here",
                        frozen, KEYS)
                .isLessThan(Duration.ofMillis(1).toNanos());
    }

    @Test
    @DisplayName("writes made while a snapshot is being written are neither in it nor lost")
    void writesDuringASnapshotAreKeptOutOfItAndKept() {
        KvStateMachine machine = filled(100);
        StateCapture capture = machine.capture();
        machine.apply(101, put("key-1", "changed"));
        machine.apply(102, put("late", "arrival"));

        KvStateMachine fromSnapshot = new KvStateMachine();
        fromSnapshot.restore(capture.serialize());
        capture.close();

        assertThat(fromSnapshot.get(Bytes.ofUtf8("key-1"))).contains(Bytes.ofUtf8("v".repeat(VALUE_BYTES)));
        assertThat(fromSnapshot.get(Bytes.ofUtf8("late"))).isEmpty();
        assertThat(machine.get(Bytes.ofUtf8("key-1"))).contains(Bytes.ofUtf8("changed"));
        assertThat(machine.get(Bytes.ofUtf8("late"))).contains(Bytes.ofUtf8("arrival"));
        assertThat(machine.size()).isEqualTo(101);
    }
}
