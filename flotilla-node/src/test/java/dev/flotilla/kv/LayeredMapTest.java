/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.kv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.flotilla.testing.SeededInputs;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.SplittableRandom;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LayeredMapTest {

    private static String key(SplittableRandom random) {
        return "k" + random.nextInt(12);
    }

    private static List<Map.Entry<String, Integer>> contents(LayeredMap<String, Integer> map) {
        List<Map.Entry<String, Integer>> seen = new ArrayList<>();
        map.forEach((key, value) -> seen.add(Map.entry(key, value)));
        return seen;
    }

    @Test
    @DisplayName("interleaving writes, deletes, scans, freezes and releases always matches a plain map")
    void behavesLikeAPlainMapWhateverIsFrozen() {
        SplittableRandom random = SeededInputs.random();
        for (int attempt = 0; attempt < 300; attempt++) {
            LayeredMap<String, Integer> layered = new LayeredMap<>();
            NavigableMap<String, Integer> reference = new TreeMap<>();
            LayeredMap.@Nullable Frozen<String, Integer> frozen = null;
            NavigableMap<String, Integer> expectedFrozen = new TreeMap<>();

            for (int step = 0; step < 200; step++) {
                String key = key(random);
                switch (random.nextInt(10)) {
                    case 0, 1, 2 -> {
                        int value = random.nextInt(1000);
                        assertThat(layered.put(key, value)).isEqualTo(reference.put(key, value));
                    }
                    case 3, 4 -> assertThat(layered.remove(key)).isEqualTo(reference.remove(key));
                    case 5 -> assertThat(layered.get(key)).isEqualTo(reference.get(key));
                    case 6 -> {
                        if (frozen == null) {
                            frozen = layered.freeze();
                            expectedFrozen = new TreeMap<>(reference);
                        }
                    }
                    case 7 -> {
                        if (frozen != null) {
                            assertThat(frozen.contents())
                                    .as(
                                            "seed %d, attempt %d, step %d: the frozen view moved",
                                            SeededInputs.SEED, attempt, step)
                                    .isEqualTo(expectedFrozen);
                            frozen.release();
                            frozen = null;
                        }
                    }
                    case 8 -> {
                        String from = key(random);
                        String to = key(random);
                        if (from.compareTo(to) < 0) {
                            List<Map.Entry<String, Integer>> scanned = new ArrayList<>();
                            layered.forEachInRange(from, to, (k, v) -> scanned.add(Map.entry(k, v)));
                            assertThat(scanned)
                                    .containsExactlyElementsOf(
                                            reference.subMap(from, to).entrySet());
                        }
                    }
                    default -> {
                        if (frozen == null && random.nextInt(4) == 0) {
                            NavigableMap<String, Integer> replacement = new TreeMap<>(Map.of("r", step));
                            layered.replaceWith(replacement);
                            reference.clear();
                            reference.putAll(replacement);
                        }
                    }
                }
                assertThat(layered.size())
                        .as("seed %d, attempt %d, step %d", SeededInputs.SEED, attempt, step)
                        .isEqualTo(reference.size());
                assertThat(contents(layered)).containsExactlyElementsOf(reference.entrySet());
            }
        }
    }

    @Test
    @DisplayName("a restore while a view is frozen leaves that view intact for whoever is still reading it")
    void aRestoreDoesNotDisturbAFrozenView() {
        LayeredMap<String, Integer> layered = new LayeredMap<>();
        layered.put("a", 1);
        LayeredMap.Frozen<String, Integer> frozen = layered.freeze();

        layered.replaceWith(new TreeMap<>(Map.of("b", 2)));
        frozen.release();
        layered.put("c", 3);

        assertThat(frozen.contents()).containsExactly(Map.entry("a", 1));
        assertThat(contents(layered)).containsExactly(Map.entry("b", 2), Map.entry("c", 3));
    }

    @Test
    @DisplayName("only one view can be frozen at a time")
    void aSecondFreezeIsRefused() {
        LayeredMap<String, Integer> layered = new LayeredMap<>();
        layered.freeze();

        assertThatThrownBy(layered::freeze).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("releasing a view folds the writes made meanwhile back in, so the next freeze starts clean")
    void releasingMergesTheOverlay() {
        LayeredMap<String, Integer> layered = new LayeredMap<>();
        layered.put("a", 1);
        LayeredMap.Frozen<String, Integer> frozen = layered.freeze();
        layered.put("b", 2);
        layered.remove("a");

        frozen.release();
        LayeredMap.Frozen<String, Integer> next = layered.freeze();

        assertThat(next.contents()).containsExactly(Map.entry("b", 2));
    }
}
