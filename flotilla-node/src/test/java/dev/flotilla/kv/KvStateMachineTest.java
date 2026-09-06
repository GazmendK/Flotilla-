/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.kv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.flotilla.core.Bytes;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class KvStateMachineTest {

    private final KvStateMachine machine = new KvStateMachine();

    private static Bytes b(String text) {
        return Bytes.ofUtf8(text);
    }

    private KvResponse run(Command command) {
        return machine.execute(command);
    }

    @Test
    void putReturnsThePreviousValue() {
        assertThat(run(Command.put("k", "one"))).isEqualTo(KvResponse.ABSENT);
        assertThat(run(Command.put("k", "two"))).isEqualTo(new KvResponse.Value(b("one")));
        assertThat(machine.get(b("k"))).contains(b("two"));
    }

    @Test
    void deleteReturnsWhatItRemoved() {
        run(Command.put("k", "v"));

        assertThat(run(Command.delete("k"))).isEqualTo(new KvResponse.Value(b("v")));
        assertThat(run(Command.delete("k")))
                .as("deleting a key that is not there is not an error")
                .isEqualTo(KvResponse.ABSENT);
        assertThat(machine.size()).isZero();
    }

    @Test
    void getOfAnAbsentKeyIsEmptyRatherThanAnError() {
        assertThat(run(Command.get("nothing"))).isEqualTo(KvResponse.ABSENT);
    }

    @Test
    @DisplayName("an empty key and an empty value are values, not absence")
    void emptyKeysAndValuesAreRealEntries() {
        assertThat(run(new Command.Put(Bytes.EMPTY, Bytes.EMPTY))).isEqualTo(KvResponse.ABSENT);

        assertThat(run(new Command.Get(Bytes.EMPTY))).isEqualTo(new KvResponse.Value(Bytes.EMPTY));
        assertThat(machine.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("compare-and-swap only writes when the expectation held")
    void compareAndSwapChecksBeforeItWrites() {
        run(Command.put("k", "v"));

        assertThat(run(new Command.CompareAndSwap(b("k"), b("wrong"), b("new"))))
                .isEqualTo(new KvResponse.Swapped(false));
        assertThat(machine.get(b("k"))).contains(b("v"));

        assertThat(run(new Command.CompareAndSwap(b("k"), b("v"), b("new")))).isEqualTo(new KvResponse.Swapped(true));
        assertThat(machine.get(b("k"))).contains(b("new"));
    }

    @Test
    @DisplayName("an absent expectation means create-if-absent, which is how a lock is taken")
    void compareAndSwapCreatesWhenNothingIsExpected() {
        assertThat(run(new Command.CompareAndSwap(b("lock"), null, b("owner-1"))))
                .isEqualTo(new KvResponse.Swapped(true));

        assertThat(run(new Command.CompareAndSwap(b("lock"), null, b("owner-2"))))
                .as("a second holder must not be able to take a lock that exists")
                .isEqualTo(new KvResponse.Swapped(false));
        assertThat(machine.get(b("lock"))).contains(b("owner-1"));
    }

    @Test
    @DisplayName("an absent new value means delete-if-equal, which is how a lock is released")
    void compareAndSwapDeletesWhenNoValueIsGiven() {
        run(Command.put("lock", "owner-1"));

        assertThat(run(new Command.CompareAndSwap(b("lock"), b("owner-2"), null)))
                .as("only the holder may release the lock")
                .isEqualTo(new KvResponse.Swapped(false));
        assertThat(run(new Command.CompareAndSwap(b("lock"), b("owner-1"), null)))
                .isEqualTo(new KvResponse.Swapped(true));
        assertThat(machine.size()).isZero();
    }

    @Test
    void scanReturnsAHalfOpenRangeInKeyOrder() {
        for (String key : List.of("a", "b", "c", "d", "e")) {
            run(Command.put(key, key.toUpperCase(Locale.ROOT)));
        }

        KvResponse response = run(new Command.Scan(b("b"), b("e"), 10));

        assertThat(response)
                .isEqualTo(new KvResponse.Entries(List.of(
                        new KeyValue(b("b"), b("B")), new KeyValue(b("c"), b("C")), new KeyValue(b("d"), b("D")))));
    }

    @Test
    void scanRespectsItsLimitAndDegenerateRanges() {
        for (String key : List.of("a", "b", "c")) {
            run(Command.put(key, key));
        }

        assertThat(run(new Command.Scan(b("a"), b("z"), 2)))
                .isEqualTo(new KvResponse.Entries(List.of(new KeyValue(b("a"), b("a")), new KeyValue(b("b"), b("b")))));
        assertThat(run(new Command.Scan(b("a"), b("z"), 0))).isEqualTo(new KvResponse.Entries(List.of()));
        assertThat(run(new Command.Scan(b("z"), b("a"), 10))).isEqualTo(new KvResponse.Entries(List.of()));
    }

    @Test
    void aLargeValueSurvivesUnchanged() {
        byte[] large = new byte[1 << 20];
        for (int i = 0; i < large.length; i++) {
            large[i] = (byte) i;
        }
        Bytes value = Bytes.copyOf(large);

        run(new Command.Put(b("big"), value));

        assertThat(machine.get(b("big"))).contains(value);

        KvStateMachine restored = new KvStateMachine();
        restored.restore(machine.snapshot());
        assertThat(restored.get(b("big"))).contains(value);
    }

    @Test
    @DisplayName("apply advances the applied index and answers in encoded form")
    void applyGoesThroughTheCodec() {
        Bytes response = machine.apply(7, CommandCodec.encode(Command.put("k", "v")));

        assertThat(CommandCodec.decodeResponse(response)).isEqualTo(KvResponse.ABSENT);
        assertThat(machine.lastAppliedIndex()).isEqualTo(7);
    }

    @Test
    void applyingSomethingThatIsNotACommandIsReported() {
        assertThatThrownBy(() -> machine.apply(1, b("not a command"))).isInstanceOf(MalformedCommandException.class);
    }
}
