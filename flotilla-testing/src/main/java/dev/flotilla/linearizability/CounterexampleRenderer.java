/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.linearizability;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

public final class CounterexampleRenderer {

    private static final int MAX_ROWS = 24;
    private static final int CELL = 2;

    private CounterexampleRenderer() {}

    public static <S, I, O> String render(Model<S, I, O> model, Counterexample<S, I, O> counterexample) {
        List<Operation<I, O>> rows = rowsAround(counterexample);
        Map<Integer, Integer> position = new HashMap<>();
        List<Operation<I, O>> longest = counterexample.longestLinearization();
        for (int index = 0; index < longest.size(); index++) {
            position.put(longest.get(index).id(), index + 1);
        }

        TreeSet<Long> instants = new TreeSet<>();
        for (Operation<I, O> row : rows) {
            instants.add(row.call());
            if (!row.isIndeterminate()) {
                instants.add(row.returned());
            }
        }
        List<Long> ranked = new ArrayList<>(instants);
        int width = (ranked.size() - 1) * CELL + 2;

        StringBuilder out = new StringBuilder();
        out.append("NOT LINEARIZABLE on ").append(counterexample.partition()).append("\n\n");
        out.append(String.format("  %-8s %-" + width + "s  %-4s %s%n", "process", "timeline", "", "operation"));
        for (Operation<I, O> row : rows) {
            String marker = row.id() == counterexample.culprit().id()
                    ? "X"
                    : position.containsKey(row.id()) ? String.valueOf(position.get(row.id())) : "";
            out.append(String.format(
                    "  %-8s %s  %-4s %s%n",
                    "p" + row.process(),
                    bar(row, ranked, width),
                    marker,
                    model.describeOperation(row.input(), row.output())));
        }
        if (rows.size() < counterexample.operations().size()) {
            out.append("  (")
                    .append(counterexample.operations().size() - rows.size())
                    .append(" operations far from the violation are not shown)\n");
        }

        Operation<I, O> culprit = counterexample.culprit();
        out.append('\n')
                .append("The longest order consistent with the model places ")
                .append(longest.size())
                .append(" of ")
                .append(counterexample.operations().size())
                .append(" operations (numbered above) and leaves the state at ")
                .append(model.describeState(counterexample.stateAfterLongest()))
                .append(".\n")
                .append("The operation marked X, ")
                .append(model.describeOperation(culprit.input(), culprit.output()))
                .append(" by p")
                .append(culprit.process())
                .append(", cannot be placed after it, and no order the search tried got further.\n");
        return out.toString();
    }

    private static <S, I, O> List<Operation<I, O>> rowsAround(Counterexample<S, I, O> counterexample) {
        List<Operation<I, O>> all = new ArrayList<>(counterexample.operations());
        all.sort(Comparator.comparingLong((Operation<I, O> operation) -> operation.call())
                .thenComparingInt(Operation::id));
        if (all.size() <= MAX_ROWS) {
            return all;
        }
        long focus = counterexample.culprit().call();
        List<Operation<I, O>> nearest = new ArrayList<>(all);
        nearest.sort(Comparator.comparingLong((Operation<I, O> operation) -> distance(operation, focus))
                .thenComparingInt(Operation::id));
        List<Operation<I, O>> chosen = new ArrayList<>(nearest.subList(0, MAX_ROWS));
        if (!chosen.contains(counterexample.culprit())) {
            chosen.set(MAX_ROWS - 1, counterexample.culprit());
        }
        chosen.sort(Comparator.comparingLong((Operation<I, O> operation) -> operation.call())
                .thenComparingInt(Operation::id));
        return chosen;
    }

    private static <I, O> long distance(Operation<I, O> operation, long focus) {
        if (operation.call() <= focus && focus <= operation.returned()) {
            return 0;
        }
        return operation.call() > focus ? operation.call() - focus : focus - operation.returned();
    }

    private static <I, O> String bar(Operation<I, O> operation, List<Long> ranked, int width) {
        char[] cells = " ".repeat(width).toCharArray();
        int start = ranked.indexOf(operation.call()) * CELL;
        int end = operation.isIndeterminate() ? width - 1 : ranked.indexOf(operation.returned()) * CELL;
        for (int column = start; column <= end; column++) {
            cells[column] = '-';
        }
        cells[start] = '[';
        cells[end] = operation.isIndeterminate() ? '>' : start == end ? '|' : ']';
        return new String(cells);
    }
}
