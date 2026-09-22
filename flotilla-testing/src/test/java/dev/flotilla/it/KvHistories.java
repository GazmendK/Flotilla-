/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.it;

import dev.flotilla.client.HistoryRecorder;
import dev.flotilla.core.Bytes;
import dev.flotilla.kv.Command;
import dev.flotilla.kv.KvResponse;
import dev.flotilla.linearizability.KvModel;
import dev.flotilla.linearizability.Operation;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

final class KvHistories {

    private KvHistories() {}

    static List<Operation<KvModel.Input, KvModel.Output>> operations(List<HistoryRecorder.Event> events) {
        Map<Long, HistoryRecorder.Event> invoked = new LinkedHashMap<>();
        Map<Long, HistoryRecorder.Event> completed = new HashMap<>();
        for (HistoryRecorder.Event event : events) {
            if (event.type() == HistoryRecorder.Type.INVOKE) {
                invoked.put(event.operation(), event);
            } else {
                completed.put(event.operation(), event);
            }
        }
        List<Operation<KvModel.Input, KvModel.Output>> operations = new ArrayList<>();
        int id = 0;
        for (HistoryRecorder.Event invoke : invoked.values()) {
            HistoryRecorder.Event outcome = completed.get(invoke.operation());
            KvModel.Input input = input(invoke.command());
            if (outcome == null || outcome.type() == HistoryRecorder.Type.INFO) {
                operations.add(Operation.indeterminate(id++, invoke.process(), input, invoke.nanos()));
            } else if (outcome.type() == HistoryRecorder.Type.OK) {
                operations.add(Operation.completed(
                        id++,
                        invoke.process(),
                        input,
                        output(Objects.requireNonNull(outcome.response())),
                        invoke.nanos(),
                        outcome.nanos()));
            }
        }
        return operations;
    }

    static long completedReads(List<Operation<KvModel.Input, KvModel.Output>> operations) {
        return operations.stream()
                .filter(operation -> operation.input() instanceof KvModel.Get && !operation.isIndeterminate())
                .count();
    }

    static long completedWrites(List<Operation<KvModel.Input, KvModel.Output>> operations) {
        return operations.stream()
                .filter(operation -> !(operation.input() instanceof KvModel.Get) && !operation.isIndeterminate())
                .count();
    }

    static long unknown(List<Operation<KvModel.Input, KvModel.Output>> operations) {
        return operations.stream().filter(Operation::isIndeterminate).count();
    }

    private static KvModel.Input input(Command command) {
        return switch (command) {
            case Command.Put put ->
                new KvModel.Put(put.key().toUtf8(), put.value().toUtf8());
            case Command.Get get -> new KvModel.Get(get.key().toUtf8());
            case Command.Delete delete -> new KvModel.Delete(delete.key().toUtf8());
            case Command.CompareAndSwap swap ->
                new KvModel.CompareAndSwap(swap.key().toUtf8(), utf8(swap.expected()), utf8(swap.value()));
            case Command.Scan scan -> throw new IllegalArgumentException("these workloads issue no scans: " + scan);
        };
    }

    private static KvModel.Output output(KvResponse response) {
        return switch (response) {
            case KvResponse.Value value -> new KvModel.Value(utf8(value.value()));
            case KvResponse.Swapped swapped -> new KvModel.Swapped(swapped.swapped());
            default -> throw new IllegalArgumentException("unexpected response " + response);
        };
    }

    private static @Nullable String utf8(@Nullable Bytes bytes) {
        return bytes == null ? null : bytes.toUtf8();
    }
}
