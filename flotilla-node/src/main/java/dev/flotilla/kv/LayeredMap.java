/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.kv;

import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.BiPredicate;
import org.jspecify.annotations.Nullable;

final class LayeredMap<K extends Comparable<K>, V> {

    private record Slot<V>(@Nullable V value) {}

    private NavigableMap<K, V> base = new TreeMap<>();

    @Nullable
    private NavigableMap<K, Slot<V>> overlay;

    private int size;
    private long generation;
    private volatile long releasedGeneration = -1;

    @Nullable
    V get(K key) {
        mergeIfReleased();
        NavigableMap<K, Slot<V>> layer = overlay;
        if (layer != null) {
            Slot<V> slot = layer.get(key);
            if (slot != null) {
                return slot.value();
            }
        }
        return base.get(key);
    }

    @Nullable
    V put(K key, V value) {
        Objects.requireNonNull(value, "value");
        V previous = get(key);
        NavigableMap<K, Slot<V>> layer = overlay;
        if (layer == null) {
            base.put(key, value);
        } else {
            layer.put(key, new Slot<>(value));
        }
        if (previous == null) {
            size++;
        }
        return previous;
    }

    @Nullable
    V remove(K key) {
        V previous = get(key);
        if (previous == null) {
            return null;
        }
        NavigableMap<K, Slot<V>> layer = overlay;
        if (layer == null) {
            base.remove(key);
        } else {
            layer.put(key, new Slot<>(null));
        }
        size--;
        return previous;
    }

    int size() {
        mergeIfReleased();
        return size;
    }

    void forEachInRange(K fromInclusive, K toExclusive, BiPredicate<K, V> consumer) {
        mergeIfReleased();
        NavigableMap<K, Slot<V>> layer = overlay;
        merge(
                base.subMap(fromInclusive, true, toExclusive, false),
                layer == null ? Collections.emptyNavigableMap() : layer.subMap(fromInclusive, true, toExclusive, false),
                consumer);
    }

    void forEach(BiPredicate<K, V> consumer) {
        mergeIfReleased();
        NavigableMap<K, Slot<V>> layer = overlay;
        merge(base, layer == null ? Collections.emptyNavigableMap() : layer, consumer);
    }

    NavigableMap<K, V> view() {
        mergeIfReleased();
        if (overlay == null) {
            return Collections.unmodifiableNavigableMap(base);
        }
        NavigableMap<K, V> merged = new TreeMap<>();
        forEach((key, value) -> {
            merged.put(key, value);
            return true;
        });
        return merged;
    }

    Frozen<K, V> freeze() {
        mergeIfReleased();
        if (overlay != null) {
            throw new IllegalStateException("a frozen view is still being read; release it before taking another");
        }
        generation++;
        overlay = new TreeMap<>();
        return new Frozen<>(this, Collections.unmodifiableNavigableMap(base), generation);
    }

    void replaceWith(NavigableMap<K, V> contents) {
        base = new TreeMap<>(contents);
        overlay = null;
        size = base.size();
        generation++;
    }

    boolean isFrozen() {
        return overlay != null;
    }

    record Frozen<K extends Comparable<K>, V>(LayeredMap<K, V> owner, NavigableMap<K, V> contents, long generation) {
        void release() {
            owner.releasedGeneration = generation;
        }
    }

    private void mergeIfReleased() {
        NavigableMap<K, Slot<V>> layer = overlay;
        if (layer == null || releasedGeneration != generation) {
            return;
        }
        for (Map.Entry<K, Slot<V>> change : layer.entrySet()) {
            V value = change.getValue().value();
            if (value == null) {
                base.remove(change.getKey());
            } else {
                base.put(change.getKey(), value);
            }
        }
        overlay = null;
    }

    private static <K extends Comparable<K>, V> void merge(
            NavigableMap<K, V> lower, NavigableMap<K, Slot<V>> upper, BiPredicate<K, V> consumer) {
        Iterator<Map.Entry<K, V>> below = lower.entrySet().iterator();
        Iterator<Map.Entry<K, Slot<V>>> above = upper.entrySet().iterator();
        Map.Entry<K, V> nextBelow = advance(below);
        Map.Entry<K, Slot<V>> nextAbove = advance(above);
        while (nextBelow != null || nextAbove != null) {
            if (nextAbove == null || (nextBelow != null && nextBelow.getKey().compareTo(nextAbove.getKey()) < 0)) {
                if (nextBelow != null && !consumer.test(nextBelow.getKey(), nextBelow.getValue())) {
                    return;
                }
                nextBelow = advance(below);
                continue;
            }
            if (nextBelow != null && nextBelow.getKey().compareTo(nextAbove.getKey()) == 0) {
                nextBelow = advance(below);
            }
            V value = nextAbove.getValue().value();
            if (value != null && !consumer.test(nextAbove.getKey(), value)) {
                return;
            }
            nextAbove = advance(above);
        }
    }

    private static <E> @Nullable E advance(Iterator<E> iterator) {
        return iterator.hasNext() ? iterator.next() : null;
    }
}
