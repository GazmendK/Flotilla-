/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.server;

import dev.flotilla.core.Bytes;
import dev.flotilla.kv.StateMachine;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

final class RecordingStateMachine implements StateMachine {

    private final List<String> applied = new CopyOnWriteArrayList<>();
    private final List<Long> indexes = new CopyOnWriteArrayList<>();

    @Nullable
    private volatile CountDownLatch gate;

    @Override
    public void apply(long index, Bytes command) {
        CountDownLatch waiting = gate;
        if (waiting != null) {
            try {
                waiting.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        indexes.add(index);
        applied.add(command.toUtf8());
    }

    void blockUntilReleased(CountDownLatch latch) {
        gate = latch;
    }

    void stopBlocking() {
        gate = null;
    }

    List<String> applied() {
        return List.copyOf(applied);
    }

    List<Long> indexes() {
        return List.copyOf(indexes);
    }
}
