/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.linearizability;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongSupplier;
import org.jspecify.annotations.Nullable;

public final class LinearizabilityChecker {

    private static final int DEADLINE_CHECK_MASK = 1023;

    private final LongSupplier clock;

    public LinearizabilityChecker() {
        this(System::nanoTime);
    }

    public LinearizabilityChecker(LongSupplier clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public <S, I, O> CheckResult<S, I, O> check(Model<S, I, O> model, List<Operation<I, O>> history, Duration budget) {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(history, "history");
        Objects.requireNonNull(budget, "budget");
        long start = clock.getAsLong();
        long deadline = budget.toNanos() >= Long.MAX_VALUE - start ? Long.MAX_VALUE : start + budget.toNanos();

        List<Operation<I, O>> relevant = new ArrayList<>(history.size());
        for (Operation<I, O> operation : history) {
            if (!(operation.isIndeterminate() && model.isReadOnly(operation.input()))) {
                relevant.add(operation);
            }
        }
        List<List<Operation<I, O>>> partitions = model.partition(relevant);
        int undecided = 0;
        for (List<Operation<I, O>> partition : partitions) {
            Verdict<S, I, O> verdict = checkPartition(model, partition, deadline);
            switch (verdict) {
                case Verdict.Linearizable<S, I, O> ignored -> {}
                case Verdict.Undecided<S, I, O> ignored -> undecided++;
                case Verdict.Violated<S, I, O> violated -> {
                    return new CheckResult<>(
                            CheckResult.Outcome.NOT_LINEARIZABLE,
                            partitions.size(),
                            undecided,
                            history.size(),
                            elapsedSince(start),
                            violated.counterexample());
                }
            }
        }
        CheckResult.Outcome outcome = undecided > 0 ? CheckResult.Outcome.UNKNOWN : CheckResult.Outcome.LINEARIZABLE;
        return new CheckResult<>(outcome, partitions.size(), undecided, history.size(), elapsedSince(start), null);
    }

    private Duration elapsedSince(long start) {
        return Duration.ofNanos(Math.max(0, clock.getAsLong() - start));
    }

    private sealed interface Verdict<S, I, O> {
        record Linearizable<S, I, O>() implements Verdict<S, I, O> {}

        record Undecided<S, I, O>() implements Verdict<S, I, O> {}

        record Violated<S, I, O>(Counterexample<S, I, O> counterexample) implements Verdict<S, I, O> {}
    }

    private static final class Node {
        private final int operation;
        private final boolean call;

        @Nullable
        private final Node match;

        private Node prev = this;
        private Node next = this;

        private Node(int operation, boolean call, @Nullable Node match) {
            this.operation = operation;
            this.call = call;
            this.match = match;
        }
    }

    private record Frame<S>(Node entry, S state) {}

    private record Event(long time, boolean call, int operation) {
        static final Comparator<Event> ORDER = Comparator.comparingLong(Event::time)
                .thenComparing(event -> !event.call())
                .thenComparingInt(Event::operation);
    }

    private <S, I, O> Verdict<S, I, O> checkPartition(
            Model<S, I, O> model, List<Operation<I, O>> operations, long deadline) {
        if (operations.isEmpty()) {
            return new Verdict.Linearizable<>();
        }
        Node head = new Node(-1, false, null);
        Node tail = new Node(-1, false, null);
        link(head, tail, events(operations));

        BitSet linearized = new BitSet(operations.size());
        BitSet uncertain = new BitSet(operations.size());
        int observedLeft = 0;
        for (int index = 0; index < operations.size(); index++) {
            if (operations.get(index).isIndeterminate()) {
                uncertain.set(index);
            } else {
                observedLeft++;
            }
        }
        Map<Visit<S>, List<BitSet>> seen = new HashMap<>();
        Deque<Frame<S>> calls = new ArrayDeque<>();
        S state = model.initialState();
        Node entry = head.next;

        int deepest = -1;
        List<Integer> longest = List.of();
        S stateAfterLongest = state;
        int culprit = -1;
        long iterations = 0;

        while (observedLeft > 0) {
            if ((iterations++ & DEADLINE_CHECK_MASK) == 0 && clock.getAsLong() >= deadline) {
                return new Verdict.Undecided<>();
            }
            if (entry == tail) {
                throw new IllegalStateException("walked off the end of the history without meeting a return");
            }
            if (entry.call) {
                Operation<I, O> operation = operations.get(entry.operation);
                Optional<S> next = model.step(state, operation.input(), operation.output());
                if (next.isPresent() && isNew(seen, linearized, uncertain, entry.operation, next.get())) {
                    calls.push(new Frame<>(entry, state));
                    state = next.get();
                    linearized.set(entry.operation);
                    if (!operation.isIndeterminate()) {
                        observedLeft--;
                    }
                    lift(entry);
                    entry = head.next;
                } else {
                    entry = entry.next;
                }
                continue;
            }

            if (calls.size() > deepest) {
                deepest = calls.size();
                longest = order(calls);
                stateAfterLongest = state;
                culprit = entry.operation;
            }
            if (calls.isEmpty()) {
                return violated(model, operations, longest, stateAfterLongest, culprit);
            }
            Frame<S> top = calls.pop();
            entry = top.entry();
            state = top.state();
            linearized.clear(entry.operation);
            if (!uncertain.get(entry.operation)) {
                observedLeft++;
            }
            unlift(entry);
            entry = entry.next;
        }
        return new Verdict.Linearizable<>();
    }

    private static <I, O> List<Event> events(List<Operation<I, O>> operations) {
        List<Event> events = new ArrayList<>(operations.size() * 2);
        for (int index = 0; index < operations.size(); index++) {
            Operation<I, O> operation = operations.get(index);
            events.add(new Event(operation.call(), true, index));
            events.add(new Event(operation.returned(), false, index));
        }
        events.sort(Event.ORDER);
        return events;
    }

    private static void link(Node head, Node tail, List<Event> events) {
        Map<Integer, Node> returns = new HashMap<>();
        for (Event event : events) {
            if (!event.call()) {
                returns.put(event.operation(), new Node(event.operation(), false, null));
            }
        }
        Node last = head;
        for (Event event : events) {
            Node returned = Objects.requireNonNull(returns.get(event.operation()));
            Node node = event.call() ? new Node(event.operation(), true, returned) : returned;
            last.next = node;
            node.prev = last;
            last = node;
        }
        last.next = tail;
        tail.prev = last;
    }

    private record Visit<S>(BitSet determinate, S state) {}

    private static <S> boolean isNew(
            Map<Visit<S>, List<BitSet>> seen, BitSet linearized, BitSet uncertain, int operation, S state) {
        BitSet after = (BitSet) linearized.clone();
        after.set(operation);
        BitSet determinate = (BitSet) after.clone();
        determinate.andNot(uncertain);
        BitSet indeterminate = after;
        indeterminate.and(uncertain);

        List<BitSet> visited = seen.computeIfAbsent(new Visit<>(determinate, state), ignored -> new ArrayList<>());
        for (BitSet earlier : visited) {
            if (isSubset(earlier, indeterminate)) {
                return false;
            }
        }
        visited.removeIf(later -> isSubset(indeterminate, later));
        visited.add(indeterminate);
        return true;
    }

    private static boolean isSubset(BitSet smaller, BitSet larger) {
        BitSet outside = (BitSet) smaller.clone();
        outside.andNot(larger);
        return outside.isEmpty();
    }

    private static void lift(Node call) {
        Node ret = Objects.requireNonNull(call.match);
        call.prev.next = call.next;
        call.next.prev = call.prev;
        ret.prev.next = ret.next;
        ret.next.prev = ret.prev;
    }

    private static void unlift(Node call) {
        Node ret = Objects.requireNonNull(call.match);
        ret.prev.next = ret;
        ret.next.prev = ret;
        call.prev.next = call;
        call.next.prev = call;
    }

    private static <S> List<Integer> order(Deque<Frame<S>> calls) {
        List<Integer> order = new ArrayList<>(calls.size());
        Iterator<Frame<S>> bottomUp = calls.descendingIterator();
        while (bottomUp.hasNext()) {
            order.add(bottomUp.next().entry().operation);
        }
        return order;
    }

    private static <S, I, O> Verdict<S, I, O> violated(
            Model<S, I, O> model, List<Operation<I, O>> operations, List<Integer> longest, S state, int culprit) {
        List<Operation<I, O>> placed = new ArrayList<>(longest.size());
        for (int index : longest) {
            placed.add(operations.get(index));
        }
        return new Verdict.Violated<>(new Counterexample<>(
                model.describePartition(operations), operations, placed, state, operations.get(culprit)));
    }
}
