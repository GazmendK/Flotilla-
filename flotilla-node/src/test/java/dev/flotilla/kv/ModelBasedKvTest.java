/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.kv;

import static org.assertj.core.api.Assertions.assertThat;

import dev.flotilla.core.Bytes;
import dev.flotilla.testing.SeededInputs;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SplittableRandom;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ModelBasedKvTest {

    private static final List<String> KEYS = List.of("a", "b", "c", "");
    private static final List<String> VALUES = List.of("1", "2", "3");

    private static Bytes pick(SplittableRandom random, List<String> choices) {
        return Bytes.ofUtf8(choices.get(random.nextInt(choices.size())));
    }

    @Nullable
    private static Bytes pickOrAbsent(SplittableRandom random) {
        return random.nextInt(4) == 0 ? null : pick(random, VALUES);
    }

    private static List<Command> program(SplittableRandom random) {
        int length = random.nextInt(0, 61);
        List<Command> commands = new ArrayList<>(length);
        for (int i = 0; i < length; i++) {
            Bytes key = pick(random, KEYS);
            commands.add(
                    switch (random.nextInt(4)) {
                        case 0 -> new Command.Put(key, pick(random, VALUES));
                        case 1 -> new Command.Delete(key);
                        case 2 -> new Command.Get(key);
                        default -> new Command.CompareAndSwap(key, pickOrAbsent(random), pickOrAbsent(random));
                    });
        }
        return commands;
    }

    @Test
    @DisplayName("every answer matches what a plain map would have said")
    void theStateMachineMatchesAReferenceMap() {
        SplittableRandom random = SeededInputs.random();
        for (int attempt = 0; attempt < 2000; attempt++) {
            List<Command> program = program(random);
            KvStateMachine machine = new KvStateMachine();
            Map<Bytes, Bytes> model = new HashMap<>();

            long index = 1;
            for (Command command : program) {
                KvResponse expected = applyToModel(model, command);
                KvResponse actual = CommandCodec.decodeResponse(
                        machine.apply(index++, CommandCodec.encode(KvRequest.anonymous(command))));

                assertThat(actual)
                        .as("seed %d, attempt %d, after %s", SeededInputs.SEED, attempt, command)
                        .isEqualTo(expected);
            }

            assertThat(machine.size())
                    .as("seed %d, attempt %d", SeededInputs.SEED, attempt)
                    .isEqualTo(model.size());
            for (Map.Entry<Bytes, Bytes> entry : model.entrySet()) {
                assertThat(machine.get(entry.getKey())).contains(entry.getValue());
            }
        }
    }

    @Test
    @DisplayName("a snapshot restores to the same state whatever produced it")
    void snapshotsRestoreTheSameState() {
        SplittableRandom random = SeededInputs.random();
        for (int attempt = 0; attempt < 500; attempt++) {
            KvStateMachine original = new KvStateMachine();
            long index = 1;
            for (Command command : program(random)) {
                original.apply(index++, CommandCodec.encode(KvRequest.anonymous(command)));
            }

            KvStateMachine restored = new KvStateMachine();
            restored.restore(original.snapshot());

            assertThat(restored.snapshot())
                    .as("seed %d, attempt %d", SeededInputs.SEED, attempt)
                    .isEqualTo(original.snapshot());
            assertThat(restored.entries()).isEqualTo(original.entries());
            assertThat(restored.lastAppliedIndex()).isEqualTo(original.lastAppliedIndex());
        }
    }

    @Test
    @DisplayName("every command that can be built can be encoded and read back")
    void commandsRoundTripThroughTheCodec() {
        SplittableRandom random = SeededInputs.random();
        for (int attempt = 0; attempt < 1000; attempt++) {
            for (Command command : program(random)) {
                assertThat(CommandCodec.decodeCommand(CommandCodec.encode(command)))
                        .as("seed %d, attempt %d", SeededInputs.SEED, attempt)
                        .isEqualTo(command);
            }
        }
    }

    private static KvResponse applyToModel(Map<Bytes, Bytes> model, Command command) {
        return switch (command) {
            case Command.Put put -> KvResponse.of(model.put(put.key(), put.value()));
            case Command.Delete delete -> KvResponse.of(model.remove(delete.key()));
            case Command.Get get -> KvResponse.of(model.get(get.key()));
            case Command.CompareAndSwap swap -> new KvResponse.Swapped(swapInModel(model, swap));
            case Command.Scan ignored -> throw new AssertionError("this test does not generate scans");
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
