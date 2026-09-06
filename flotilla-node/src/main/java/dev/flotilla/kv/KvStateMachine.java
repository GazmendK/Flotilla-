/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.kv;

import dev.flotilla.core.Bytes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

public final class KvStateMachine implements StateMachine {

    private final NavigableMap<Bytes, Bytes> data = new TreeMap<>();

    private long lastAppliedIndex;

    @Override
    public Bytes apply(long index, Bytes command) {
        Objects.requireNonNull(command, "command");
        KvResponse response = execute(CommandCodec.decodeCommand(command));
        lastAppliedIndex = index;
        return CommandCodec.encode(response);
    }

    public KvResponse execute(Command command) {
        Objects.requireNonNull(command, "command");
        return switch (command) {
            case Command.Put put -> KvResponse.of(data.put(put.key(), put.value()));
            case Command.Delete delete -> KvResponse.of(data.remove(delete.key()));
            case Command.CompareAndSwap swap -> new KvResponse.Swapped(compareAndSwap(swap));
            case Command.Get get -> KvResponse.of(data.get(get.key()));
            case Command.Scan scan -> new KvResponse.Entries(scan(scan));
        };
    }

    @Override
    public Bytes snapshot() {
        return KvSnapshotCodec.encode(lastAppliedIndex, data);
    }

    @Override
    public void restore(Bytes snapshot) {
        KvSnapshotCodec.Snapshot restored = KvSnapshotCodec.decode(snapshot);
        data.clear();
        data.putAll(restored.data());
        lastAppliedIndex = restored.lastAppliedIndex();
    }

    @Override
    public long lastAppliedIndex() {
        return lastAppliedIndex;
    }

    public Optional<Bytes> get(Bytes key) {
        return Optional.ofNullable(data.get(Objects.requireNonNull(key, "key")));
    }

    public int size() {
        return data.size();
    }

    public List<KeyValue> entries() {
        List<KeyValue> entries = new ArrayList<>(data.size());
        for (Map.Entry<Bytes, Bytes> entry : data.entrySet()) {
            entries.add(new KeyValue(entry.getKey(), entry.getValue()));
        }
        return entries;
    }

    private boolean compareAndSwap(Command.CompareAndSwap swap) {
        @Nullable Bytes current = data.get(swap.key());
        if (!Objects.equals(current, swap.expected())) {
            return false;
        }
        if (swap.value() == null) {
            data.remove(swap.key());
        } else {
            data.put(swap.key(), swap.value());
        }
        return true;
    }

    private List<KeyValue> scan(Command.Scan scan) {
        if (scan.limit() == 0 || scan.fromInclusive().compareTo(scan.toExclusive()) >= 0) {
            return List.of();
        }
        List<KeyValue> found = new ArrayList<>();
        for (Map.Entry<Bytes, Bytes> entry : data.subMap(scan.fromInclusive(), true, scan.toExclusive(), false)
                .entrySet()) {
            if (found.size() == scan.limit()) {
                break;
            }
            found.add(new KeyValue(entry.getKey(), entry.getValue()));
        }
        return found;
    }
}
