/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.kv;

import static org.assertj.core.api.Assertions.assertThat;

import dev.flotilla.core.Bytes;
import java.time.Duration;
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

    @Test
    @DisplayName("copying the state costs a fraction of encoding it, which is why the apply loop only copies")
    void copyingIsMuchCheaperThanEncoding() {
        KvStateMachine machine = filled(KEYS);
        long warmCopy = nanosOf(machine::capture);
        long warmEncode = nanosOf(machine.capture()::serialize);
        assertThat(warmCopy + warmEncode).isPositive();

        long copyNanos = nanosOf(machine::capture);
        long encodeNanos = nanosOf(machine.capture()::serialize);

        System.out.println(
                "capture of " + KEYS + " keys: " + Duration.ofNanos(copyNanos).toMillis() + " ms, serialization: "
                        + Duration.ofNanos(encodeNanos).toMillis() + " ms");
        assertThat(copyNanos)
                .as(
                        "copying %d keys took %d ns and encoding them took %d ns; if copying is not the cheap half "
                                + "there is no reason to split the work at all",
                        KEYS, copyNanos, encodeNanos)
                .isLessThan(encodeNanos);
    }
}
