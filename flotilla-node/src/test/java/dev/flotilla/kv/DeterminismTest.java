/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.kv;

import static org.assertj.core.api.Assertions.assertThat;

import dev.flotilla.core.Bytes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DeterminismTest {

    private static final int KEYS = 200;

    private static List<Command> workload() {
        List<Command> commands = new ArrayList<>();
        for (int i = 0; i < KEYS; i++) {
            commands.add(Command.put("key-" + i, "value-" + i));
        }
        for (int i = 0; i < KEYS; i += 3) {
            commands.add(Command.delete("key-" + i));
        }
        for (int i = 1; i < KEYS; i += 7) {
            commands.add(Command.put("key-" + i, "rewritten-" + i));
        }
        return commands;
    }

    private static KvStateMachine machineWith(List<Command> commands) {
        KvStateMachine machine = new KvStateMachine();
        long index = 1;
        for (Command command : commands) {
            machine.apply(index++, CommandCodec.encode(KvRequest.anonymous(command)));
        }
        return machine;
    }

    @Test
    @DisplayName("the same log produces byte-identical snapshots on two fresh replicas")
    void theSameLogProducesTheSameBytes() {
        List<Command> commands = workload();

        Bytes first = machineWith(commands).snapshot();
        Bytes second = machineWith(commands).snapshot();

        assertThat(first).isEqualTo(second);
    }

    @Test
    @DisplayName("a replica that restored a snapshot and caught up matches one that replayed everything")
    void restoringAndCatchingUpMatchesAFullReplay() {
        List<Command> commands = workload();
        int split = commands.size() / 2;

        KvStateMachine full = machineWith(commands);

        KvStateMachine partial = machineWith(commands.subList(0, split));
        KvStateMachine caughtUp = new KvStateMachine();
        caughtUp.restore(partial.snapshot());
        long index = split + 1L;
        for (Command command : commands.subList(split, commands.size())) {
            caughtUp.apply(index++, CommandCodec.encode(KvRequest.anonymous(command)));
        }

        assertThat(caughtUp.snapshot())
                .as("this is the path a hash-ordered map diverges on: the insertion history differs")
                .isEqualTo(full.snapshot());
        assertThat(caughtUp.lastAppliedIndex()).isEqualTo(full.lastAppliedIndex());
    }

    @Test
    @DisplayName("insertion order does not survive into the snapshot")
    void thePathToAStateDoesNotChangeItsBytes() {
        List<Command> forward = new ArrayList<>();
        for (int i = 0; i < KEYS; i++) {
            forward.add(Command.put("key-" + i, "value-" + i));
        }
        List<Command> shuffled = new ArrayList<>(forward);
        Collections.shuffle(shuffled, new Random(20260906));

        assertThat(machineWith(shuffled).snapshot())
                .as("a snapshot must describe the state, never the route taken to it")
                .isEqualTo(machineWith(forward).snapshot());
    }

    @Test
    @DisplayName("a restored snapshot round-trips to the same bytes")
    void restoreIsTheInverseOfSnapshot() {
        KvStateMachine original = machineWith(workload());
        Bytes snapshot = original.snapshot();

        KvStateMachine restored = new KvStateMachine();
        restored.restore(snapshot);

        assertThat(restored.snapshot()).isEqualTo(snapshot);
        assertThat(restored.entries()).isEqualTo(original.entries());
        assertThat(restored.lastAppliedIndex()).isEqualTo(original.lastAppliedIndex());
    }
}
