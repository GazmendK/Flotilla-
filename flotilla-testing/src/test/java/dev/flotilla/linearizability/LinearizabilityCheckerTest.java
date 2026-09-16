/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.linearizability;

import static org.assertj.core.api.Assertions.assertThat;

import dev.flotilla.linearizability.CheckResult.Outcome;
import dev.flotilla.linearizability.KvModel.CompareAndSwap;
import dev.flotilla.linearizability.KvModel.Get;
import dev.flotilla.linearizability.KvModel.Input;
import dev.flotilla.linearizability.KvModel.Output;
import dev.flotilla.linearizability.KvModel.Put;
import dev.flotilla.linearizability.KvModel.Swapped;
import dev.flotilla.linearizability.KvModel.Value;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LinearizabilityCheckerTest {

    private static final Duration BUDGET = Duration.ofSeconds(30);
    private static final KvModel MODEL = new KvModel();

    private final List<Operation<Input, Output>> history = new ArrayList<>();

    private LinearizabilityCheckerTest ok(long process, Input input, Output output, long call, long returned) {
        history.add(Operation.completed(history.size(), process, input, output, call, returned));
        return this;
    }

    private LinearizabilityCheckerTest crashed(long process, Input input, long call) {
        history.add(Operation.indeterminate(history.size(), process, input, call));
        return this;
    }

    private static Value value(@Nullable String value) {
        return new Value(value);
    }

    private CheckResult<KvModel.State, Input, Output> check() {
        return new LinearizabilityChecker().check(MODEL, history, BUDGET);
    }

    @Test
    @DisplayName("a read that overlaps a write may see the new value")
    void aConcurrentReadMaySeeTheWrite() {
        ok(1, new Put("x", "1"), value(null), 0, 10).ok(2, new Get("x"), value("1"), 5, 15);

        assertThat(check().outcome()).isEqualTo(Outcome.LINEARIZABLE);
    }

    @Test
    @DisplayName("a read that overlaps a write may also still see the old value")
    void aConcurrentReadMayMissTheWrite() {
        ok(1, new Put("x", "1"), value(null), 0, 10).ok(2, new Get("x"), value(null), 5, 15);

        assertThat(check().outcome()).isEqualTo(Outcome.LINEARIZABLE);
    }

    @Test
    @DisplayName("a read that starts after a write returned must see it: the stale read")
    void aStaleReadIsCaught() {
        ok(1, new Put("x", "1"), value(null), 0, 5).ok(2, new Get("x"), value(null), 10, 15);

        CheckResult<KvModel.State, Input, Output> result = check();

        assertThat(result.outcome()).isEqualTo(Outcome.NOT_LINEARIZABLE);
        assertThat(result.counterexample().orElseThrow().culprit().input()).isEqualTo(new Get("x"));
    }

    @Test
    @DisplayName("once one reader has seen a write, a later reader cannot go back to before it")
    void readsCannotTravelBackInTime() {
        ok(1, new Put("x", "1"), value(null), 0, 100)
                .ok(2, new Get("x"), value("1"), 10, 20)
                .ok(3, new Get("x"), value(null), 30, 40);

        assertThat(check().outcome()).isEqualTo(Outcome.NOT_LINEARIZABLE);
    }

    @Test
    @DisplayName("two readers overlapping a write may see it in the order old then new")
    void readsOverlappingAWriteMayMoveForward() {
        ok(1, new Put("x", "1"), value(null), 0, 100)
                .ok(2, new Get("x"), value(null), 10, 20)
                .ok(3, new Get("x"), value("1"), 30, 40);

        assertThat(check().outcome()).isEqualTo(Outcome.LINEARIZABLE);
    }

    @Test
    @DisplayName("two concurrent compare-and-swaps from the same value cannot both succeed")
    void twoWinnersOfOneSwapAreCaught() {
        ok(1, new CompareAndSwap("x", null, "a"), new Swapped(true), 0, 10)
                .ok(2, new CompareAndSwap("x", null, "b"), new Swapped(true), 0, 10);

        assertThat(check().outcome()).isEqualTo(Outcome.NOT_LINEARIZABLE);
    }

    @Test
    @DisplayName("one winner and one loser of a swap is exactly what linearizability allows")
    void oneWinnerOfOneSwapIsFine() {
        ok(1, new CompareAndSwap("x", null, "a"), new Swapped(true), 0, 10)
                .ok(2, new CompareAndSwap("x", null, "b"), new Swapped(false), 0, 10)
                .ok(3, new Get("x"), value("a"), 20, 30);

        assertThat(check().outcome()).isEqualTo(Outcome.LINEARIZABLE);
    }

    @Test
    @DisplayName("a write whose outcome is unknown may have taken effect, even long after it was sent")
    void anIndeterminateWriteMayHaveHappened() {
        ok(1, new Put("x", "1"), value(null), 0, 5)
                .crashed(2, new Put("x", "2"), 10)
                .ok(3, new Get("x"), value("1"), 20, 25)
                .ok(3, new Get("x"), value("2"), 30, 35);

        assertThat(check().outcome()).isEqualTo(Outcome.LINEARIZABLE);
    }

    @Test
    @DisplayName("a write whose outcome is unknown may equally never have happened")
    void anIndeterminateWriteMayNotHaveHappened() {
        ok(1, new Put("x", "1"), value(null), 0, 5)
                .crashed(2, new Put("x", "2"), 10)
                .ok(3, new Get("x"), value("1"), 20, 25)
                .ok(3, new Get("x"), value("1"), 30, 35);

        assertThat(check().outcome()).isEqualTo(Outcome.LINEARIZABLE);
    }

    @Test
    @DisplayName("but an unknown write cannot be observed and then un-observed")
    void anIndeterminateWriteCannotBeUndone() {
        ok(1, new Put("x", "1"), value(null), 0, 5)
                .crashed(2, new Put("x", "2"), 10)
                .ok(3, new Get("x"), value("2"), 20, 25)
                .ok(3, new Get("x"), value("1"), 30, 35);

        assertThat(check().outcome()).isEqualTo(Outcome.NOT_LINEARIZABLE);
    }

    @Test
    @DisplayName("a write that definitely failed must not be visible afterwards")
    void aFailedWriteMustNotBeSeen() {
        History<Input, Output> recorded = new History<>();
        int write = recorded.invoke(1, new Put("x", "1"), 0);
        recorded.fail(write, 5);
        int read = recorded.invoke(2, new Get("x"), 10);
        recorded.ok(read, value("1"), 15);

        CheckResult<KvModel.State, Input, Output> result =
                new LinearizabilityChecker().check(MODEL, recorded.operations(), BUDGET);

        assertThat(result.outcome()).isEqualTo(Outcome.NOT_LINEARIZABLE);
    }

    @Test
    @DisplayName("an operation still pending when the history ends is treated as unknown, not as failed")
    void aPendingOperationIsIndeterminate() {
        History<Input, Output> recorded = new History<>();
        recorded.invoke(1, new Put("x", "1"), 0);
        int read = recorded.invoke(2, new Get("x"), 10);
        recorded.ok(read, value("1"), 15);

        assertThat(recorded.operations().getFirst().isIndeterminate()).isTrue();
        assertThat(new LinearizabilityChecker()
                        .check(MODEL, recorded.operations(), BUDGET)
                        .outcome())
                .isEqualTo(Outcome.LINEARIZABLE);
    }

    @Test
    @DisplayName("keys are checked independently, and the violation is reported on the key that has it")
    void aViolationIsLocalisedToItsKey() {
        ok(1, new Put("x", "1"), value(null), 0, 5)
                .ok(2, new Get("x"), value("1"), 10, 15)
                .ok(1, new Put("y", "1"), value(null), 0, 5)
                .ok(2, new Get("y"), value(null), 10, 15);

        CheckResult<KvModel.State, Input, Output> result = check();

        assertThat(result.outcome()).isEqualTo(Outcome.NOT_LINEARIZABLE);
        assertThat(result.counterexample().orElseThrow().partition()).isEqualTo("key \"y\"");
    }

    @Test
    @DisplayName("running out of time is reported as unknown and never as a pass")
    void runningOutOfTimeIsUnknown() {
        ok(1, new Put("x", "1"), value(null), 0, 10).ok(2, new Get("x"), value("1"), 5, 15);

        CheckResult<KvModel.State, Input, Output> result =
                new LinearizabilityChecker().check(MODEL, history, Duration.ZERO);

        assertThat(result.outcome()).isEqualTo(Outcome.UNKNOWN);
        assertThat(result.isLinearizable()).isFalse();
        assertThat(result.counterexample()).isEmpty();
    }

    @Test
    void anEmptyHistoryIsLinearizable() {
        assertThat(check().outcome()).isEqualTo(Outcome.LINEARIZABLE);
    }

    @Test
    @DisplayName("the counterexample is a readable timeline naming the operation that cannot be placed")
    void theCounterexampleReadsLikeATimeline() {
        ok(1, new Put("x", "1"), value(null), 0, 5).ok(2, new Get("x"), value(null), 10, 15);

        String rendered =
                CounterexampleRenderer.render(MODEL, check().counterexample().orElseThrow());

        assertThat(rendered)
                .contains("NOT LINEARIZABLE on key \"x\"")
                .contains("put x=\"1\" -> nil")
                .contains("get x -> nil")
                .contains("The operation marked X, get x -> nil by p2")
                .contains("leaves the state at \"1\"");
    }
}
