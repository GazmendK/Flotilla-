/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.kv;

import static org.assertj.core.api.Assertions.assertThat;

import dev.flotilla.core.Bytes;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;

class ModelBasedKvTest {

    private static Arbitrary<Bytes> keys() {
        return Arbitraries.of("a", "b", "c", "").map(Bytes::ofUtf8);
    }

    private static Arbitrary<Bytes> values() {
        return Arbitraries.of("1", "2", "3").map(Bytes::ofUtf8);
    }

    @Provide
    Arbitrary<List<Command>> programs() {
        Arbitrary<Command> put = keys().flatMap(key -> values().map(value -> new Command.Put(key, value)));
        Arbitrary<Command> delete = keys().map(Command.Delete::new);
        Arbitrary<Command> get = keys().map(Command.Get::new);
        Arbitrary<Command> swap = keys().flatMap(key -> values().injectNull(0.25)
                .flatMap(expected ->
                        values().injectNull(0.25).map(value -> new Command.CompareAndSwap(key, expected, value))));

        return Arbitraries.oneOf(put, delete, get, swap).list().ofMaxSize(60);
    }

    @Property(tries = 2000)
    @DisplayName("every answer matches what a plain map would have said")
    void theStateMachineMatchesAReferenceMap(@ForAll("programs") List<Command> program) {
        KvStateMachine machine = new KvStateMachine();
        Map<Bytes, Bytes> model = new HashMap<>();

        long index = 1;
        for (Command command : program) {
            KvResponse expected = applyToModel(model, command);
            KvResponse actual = CommandCodec.decodeResponse(
                    machine.apply(index++, CommandCodec.encode(KvRequest.anonymous(command))));

            assertThat(actual).as("after %s", command).isEqualTo(expected);
        }

        assertThat(machine.size()).isEqualTo(model.size());
        for (Map.Entry<Bytes, Bytes> entry : model.entrySet()) {
            assertThat(machine.get(entry.getKey())).contains(entry.getValue());
        }
    }

    @Property(tries = 500)
    @DisplayName("a snapshot restores to the same state whatever produced it")
    void snapshotsRestoreTheSameState(@ForAll("programs") List<Command> program) {
        KvStateMachine original = new KvStateMachine();
        long index = 1;
        for (Command command : program) {
            original.apply(index++, CommandCodec.encode(KvRequest.anonymous(command)));
        }

        KvStateMachine restored = new KvStateMachine();
        restored.restore(original.snapshot());

        assertThat(restored.snapshot()).isEqualTo(original.snapshot());
        assertThat(restored.entries()).isEqualTo(original.entries());
        assertThat(restored.lastAppliedIndex()).isEqualTo(original.lastAppliedIndex());
    }

    @Property(tries = 1000)
    @DisplayName("every command that can be built can be encoded and read back")
    void commandsRoundTripThroughTheCodec(@ForAll("programs") List<Command> program) {
        for (Command command : program) {
            assertThat(CommandCodec.decodeCommand(CommandCodec.encode(KvRequest.anonymous(command))))
                    .isEqualTo(command);
        }
    }

    private static KvResponse applyToModel(Map<Bytes, Bytes> model, Command command) {
        return switch (command) {
            case Command.Put put -> KvResponse.of(model.put(put.key(), put.value()));
            case Command.Delete delete -> KvResponse.of(model.remove(delete.key()));
            case Command.Get get -> KvResponse.of(model.get(get.key()));
            case Command.CompareAndSwap swap -> new KvResponse.Swapped(swapInModel(model, swap));
            case Command.Scan ignored -> throw new AssertionError("this property does not generate scans");
        };
    }

    private static boolean swapInModel(Map<Bytes, Bytes> model, Command.CompareAndSwap swap) {
        @Nullable Bytes current = model.get(swap.key());
        if (!Objects.equals(current, swap.expected())) {
            return false;
        }
        if (swap.value() == null) {
            model.remove(swap.key());
        } else {
            model.put(swap.key(), swap.value());
        }
        return true;
    }
}
