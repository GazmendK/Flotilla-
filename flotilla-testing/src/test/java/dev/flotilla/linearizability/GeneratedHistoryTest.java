/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.linearizability;

import static org.assertj.core.api.Assertions.assertThat;

import dev.flotilla.linearizability.CheckResult.Outcome;
import dev.flotilla.linearizability.KvModel.CompareAndSwap;
import dev.flotilla.linearizability.KvModel.Delete;
import dev.flotilla.linearizability.KvModel.Get;
import dev.flotilla.linearizability.KvModel.Input;
import dev.flotilla.linearizability.KvModel.Output;
import dev.flotilla.linearizability.KvModel.Put;
import dev.flotilla.linearizability.KvModel.Value;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SplittableRandom;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GeneratedHistoryTest {

    private static final long SEED = 20260916L;
    private static final KvModel MODEL = new KvModel();
    private static final Duration BUDGET = Duration.ofSeconds(60);
    private static final int OPERATIONS = 3000;
    private static final int KEYS = 10;
    private static final int PROCESSES = 12;

    private record Planned(
            int id,
            Input input,
            long call,
            long point,
            long returned,
            boolean indeterminate,
            boolean appliesIfIndeterminate) {}

    private static List<Operation<Input, Output>> linearizableHistory(SplittableRandom random) {
        List<Planned> planned = new ArrayList<>(OPERATIONS);
        for (int id = 0; id < OPERATIONS; id++) {
            long call = 2L * random.nextInt(OPERATIONS * 6);
            long duration = random.nextInt(40);
            long returned = call + 2 * duration + 2;
            long point = call + 1 + 2L * random.nextInt((int) duration + 1);
            boolean indeterminate = random.nextInt(20) == 0;
            planned.add(new Planned(id, input(random), call, point, returned, indeterminate, random.nextBoolean()));
        }

        List<Planned> byPoint = new ArrayList<>(planned);
        byPoint.sort(Comparator.comparingLong(Planned::point).thenComparingInt(Planned::id));
        Map<String, String> store = new HashMap<>();
        Map<Integer, Output> outputs = new HashMap<>();
        for (Planned operation : byPoint) {
            if (operation.indeterminate() && !operation.appliesIfIndeterminate()) {
                continue;
            }
            KvModel.State before = new KvModel.State(store.get(operation.input().key()));
            Output observed = observe(before, operation.input());
            Optional<KvModel.State> after = MODEL.step(before, operation.input(), observed);
            String value = after.orElseThrow().value();
            if (value == null) {
                store.remove(operation.input().key());
            } else {
                store.put(operation.input().key(), value);
            }
            outputs.put(operation.id(), observed);
        }

        List<Operation<Input, Output>> history = new ArrayList<>(OPERATIONS);
        for (Planned operation : planned) {
            long process = operation.id() % PROCESSES;
            history.add(
                    operation.indeterminate()
                            ? Operation.indeterminate(operation.id(), process, operation.input(), operation.call())
                            : Operation.completed(
                                    operation.id(),
                                    process,
                                    operation.input(),
                                    java.util.Objects.requireNonNull(outputs.get(operation.id())),
                                    operation.call(),
                                    operation.returned()));
        }
        return history;
    }

    private static Output observe(KvModel.State state, Input input) {
        return switch (input) {
            case Put ignored -> new Value(state.value());
            case Get ignored -> new Value(state.value());
            case Delete ignored -> new Value(state.value());
            case CompareAndSwap swap -> new KvModel.Swapped(java.util.Objects.equals(state.value(), swap.expected()));
        };
    }

    private static Input input(SplittableRandom random) {
        String key = "k" + random.nextInt(KEYS);
        return switch (random.nextInt(20)) {
            case 0 -> new Delete(key);
            case 1, 2, 3 -> new CompareAndSwap(key, maybeValue(random), maybeValue(random));
            case 4, 5, 6, 7, 8, 9, 10, 11 -> new Put(key, String.valueOf(random.nextInt(4)));
            default -> new Get(key);
        };
    }

    private static @Nullable String maybeValue(SplittableRandom random) {
        return random.nextInt(4) == 0 ? null : String.valueOf(random.nextInt(4));
    }

    @Test
    @DisplayName("thousands of concurrent operations produced by a real sequential execution are accepted")
    void generatedLinearizableHistoriesPass() {
        SplittableRandom random = new SplittableRandom(SEED);
        for (int attempt = 0; attempt < 5; attempt++) {
            List<Operation<Input, Output>> history = linearizableHistory(random);

            CheckResult<KvModel.State, Input, Output> result =
                    new LinearizabilityChecker().check(MODEL, history, BUDGET);

            assertThat(result.outcome())
                    .as("seed %d, attempt %d: %s", SEED, attempt, result)
                    .isEqualTo(Outcome.LINEARIZABLE);
        }
    }

    @Test
    @DisplayName("changing a single read in such a history to a value nobody wrote is always caught")
    void aSingleCorruptedReadIsCaught() {
        SplittableRandom random = new SplittableRandom(SEED);
        for (int attempt = 0; attempt < 20; attempt++) {
            List<Operation<Input, Output>> history = new ArrayList<>(linearizableHistory(random));
            List<Integer> reads = new ArrayList<>();
            for (int index = 0; index < history.size(); index++) {
                if (history.get(index).input() instanceof Get
                        && !history.get(index).isIndeterminate()) {
                    reads.add(index);
                }
            }
            int victim = reads.get(random.nextInt(reads.size()));
            Operation<Input, Output> original = history.get(victim);
            history.set(
                    victim,
                    Operation.completed(
                            original.id(),
                            original.process(),
                            original.input(),
                            new Value("never written"),
                            original.call(),
                            original.returned()));

            CheckResult<KvModel.State, Input, Output> result =
                    new LinearizabilityChecker().check(MODEL, history, BUDGET);

            assertThat(result.outcome())
                    .as("seed %d, attempt %d: corrupting operation %d went unnoticed", SEED, attempt, original.id())
                    .isEqualTo(Outcome.NOT_LINEARIZABLE);
            assertThat(result.counterexample().orElseThrow().partition())
                    .isEqualTo("key \"" + original.input().key() + "\"");
        }
    }
}
