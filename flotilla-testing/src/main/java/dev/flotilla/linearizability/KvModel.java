/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.linearizability;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

public final class KvModel implements Model<KvModel.State, KvModel.Input, KvModel.Output> {

    public sealed interface Input permits Put, Get, Delete, CompareAndSwap {
        String key();
    }

    public record Put(String key, String value) implements Input {
        public Put {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(value, "value");
        }
    }

    public record Get(String key) implements Input {
        public Get {
            Objects.requireNonNull(key, "key");
        }
    }

    public record Delete(String key) implements Input {
        public Delete {
            Objects.requireNonNull(key, "key");
        }
    }

    public record CompareAndSwap(
            String key, @Nullable String expected, @Nullable String value) implements Input {
        public CompareAndSwap {
            Objects.requireNonNull(key, "key");
        }
    }

    public sealed interface Output permits Value, Swapped {}

    public record Value(@Nullable String value) implements Output {}

    public record Swapped(boolean swapped) implements Output {}

    public record State(@Nullable String value) {}

    private record Transition(State next, Output output) {}

    @Override
    public State initialState() {
        return new State(null);
    }

    @Override
    public Optional<State> step(State state, Input input, @Nullable Output output) {
        Transition transition = apply(state, input);
        if (output != null && !output.equals(transition.output())) {
            return Optional.empty();
        }
        return Optional.of(transition.next());
    }

    @Override
    public boolean isReadOnly(Input input) {
        return input instanceof Get;
    }

    private static Transition apply(State state, Input input) {
        return switch (input) {
            case Put put -> new Transition(new State(put.value()), new Value(state.value()));
            case Get ignored -> new Transition(state, new Value(state.value()));
            case Delete ignored -> new Transition(new State(null), new Value(state.value()));
            case CompareAndSwap swap ->
                Objects.equals(state.value(), swap.expected())
                        ? new Transition(new State(swap.value()), new Swapped(true))
                        : new Transition(state, new Swapped(false));
        };
    }

    @Override
    public List<List<Operation<Input, Output>>> partition(List<Operation<Input, Output>> operations) {
        SortedMap<String, List<Operation<Input, Output>>> byKey = new TreeMap<>();
        for (Operation<Input, Output> operation : operations) {
            byKey.computeIfAbsent(operation.input().key(), ignored -> new ArrayList<>())
                    .add(operation);
        }
        List<List<Operation<Input, Output>>> partitions = new ArrayList<>(byKey.size());
        for (Map.Entry<String, List<Operation<Input, Output>>> entry : byKey.entrySet()) {
            partitions.add(List.copyOf(entry.getValue()));
        }
        return partitions;
    }

    @Override
    public String describePartition(List<Operation<Input, Output>> partition) {
        return partition.isEmpty()
                ? "no key"
                : "key " + quote(partition.getFirst().input().key());
    }

    @Override
    public String describeOperation(Input input, @Nullable Output output) {
        String call =
                switch (input) {
                    case Put put -> "put " + put.key() + "=" + quote(put.value());
                    case Get get -> "get " + get.key();
                    case Delete delete -> "delete " + delete.key();
                    case CompareAndSwap swap ->
                        "cas " + swap.key() + " " + quote(swap.expected()) + "->" + quote(swap.value());
                };
        String result =
                switch (output) {
                    case null -> "?";
                    case Value value -> quote(value.value());
                    case Swapped swapped -> swapped.swapped() ? "swapped" : "unchanged";
                };
        return call + " -> " + result;
    }

    @Override
    public String describeState(State state) {
        return quote(state.value());
    }

    private static String quote(@Nullable String value) {
        return value == null ? "nil" : "\"" + value + "\"";
    }
}
