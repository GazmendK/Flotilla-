/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class NodeIdTest {
    @ParameterizedTest
    @ValueSource(strings = {"n1", "node-1", "node_1", "a.b.c", "N", "0"})
    void acceptsReasonableIdentifiers(String value) {
        assertThat(NodeId.of(value).value()).isEqualTo(value);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "-leading-dash", ".leading-dot", "has space", "has/slash", "ümlaut"})
    @DisplayName("rejects anything that would be awkward in a file name, a metric or a log line")
    void rejectsUnsuitableIdentifiers(String value) {
        assertThatThrownBy(() -> NodeId.of(value))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid node id");
    }

    @Test
    void rejectsOverlyLongIdentifiers() {
        String tooLong = "n".repeat(65);

        assertThatThrownBy(() -> NodeId.of(tooLong)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("ordering is total, so peers can be iterated deterministically")
    void ordersDeterministically() {
        TreeSet<NodeId> sorted = new TreeSet<>();
        sorted.add(NodeId.of("n3"));
        sorted.add(NodeId.of("n1"));
        sorted.add(NodeId.of("n2"));

        assertThat(sorted).extracting(NodeId::value).containsExactly("n1", "n2", "n3");
    }

    @Test
    void printsAsThePlainIdentifier() {
        assertThat(NodeId.of("n1")).hasToString("n1");
    }
}
