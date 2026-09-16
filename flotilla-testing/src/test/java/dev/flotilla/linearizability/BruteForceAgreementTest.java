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
import dev.flotilla.linearizability.KvModel.Swapped;
import dev.flotilla.linearizability.KvModel.Value;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.SplittableRandom;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BruteForceAgreementTest {

    private static final long SEED = 20260916L;
    private static final KvModel MODEL = new KvModel();
    private static final int HISTORIES = 50_000;
    private static final int MAX_OPERATIONS = 8;

    private static boolean bruteForce(KvModel.State state, List<Operation<Input, Output>> remaining) {
        if (remaining.isEmpty()) {
            return true;
        }
        for (int index = 0; index < remaining.size(); index++) {
            Operation<Input, Output> candidate = remaining.get(index);
            boolean blocked = false;
            for (Operation<Input, Output> other : remaining) {
                if (other.id() != candidate.id() && other.returned() < candidate.call()) {
                    blocked = true;
                    break;
                }
            }
            if (blocked) {
                continue;
            }
            Optional<KvModel.State> next = MODEL.step(state, candidate.input(), candidate.output());
            if (next.isPresent()) {
                List<Operation<Input, Output>> rest = new ArrayList<>(remaining);
                rest.remove(index);
                if (bruteForce(next.get(), rest)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static @Nullable String value(SplittableRandom random) {
        return random.nextInt(3) == 0 ? null : String.valueOf(random.nextInt(2));
    }

    private static Input input(SplittableRandom random) {
        return switch (random.nextInt(8)) {
            case 0 -> new Delete("k");
            case 1, 2 -> new CompareAndSwap("k", value(random), value(random));
            case 3, 4, 5 -> new Put("k", String.valueOf(random.nextInt(2)));
            default -> new Get("k");
        };
    }

    private static Output output(SplittableRandom random, Input input) {
        return input instanceof CompareAndSwap ? new Swapped(random.nextBoolean()) : new Value(value(random));
    }

    private static List<Operation<Input, Output>> history(SplittableRandom random) {
        int size = 1 + random.nextInt(MAX_OPERATIONS);
        int uncertainty = 2 + random.nextInt(4);
        List<Operation<Input, Output>> operations = new ArrayList<>(size);
        for (int id = 0; id < size; id++) {
            Input input = input(random);
            long call = random.nextInt(10);
            if (random.nextInt(uncertainty) == 0) {
                operations.add(Operation.indeterminate(id, id, input, call));
            } else {
                operations.add(
                        Operation.completed(id, id, input, output(random, input), call, call + random.nextInt(6)));
            }
        }
        return operations;
    }

    @Test
    @DisplayName("on thousands of small histories the checker agrees with trying every order by brute force")
    void theCheckerAgreesWithBruteForce() {
        SplittableRandom random = new SplittableRandom(SEED);
        int linearizable = 0;
        for (int attempt = 0; attempt < HISTORIES; attempt++) {
            List<Operation<Input, Output>> history = history(random);
            boolean expected = bruteForce(MODEL.initialState(), history);

            Outcome actual = new LinearizabilityChecker()
                    .check(MODEL, history, Duration.ofSeconds(10))
                    .outcome();

            assertThat(actual)
                    .as("seed %d, attempt %d, history %s", SEED, attempt, history)
                    .isEqualTo(expected ? Outcome.LINEARIZABLE : Outcome.NOT_LINEARIZABLE);
            if (expected) {
                linearizable++;
            }
        }
        assertThat(linearizable)
                .as("the generator must produce both kinds, or agreement proves nothing")
                .isBetween(HISTORIES / 10, HISTORIES - HISTORIES / 10);
    }
}
