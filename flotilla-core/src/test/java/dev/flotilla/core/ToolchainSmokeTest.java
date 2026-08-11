/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ToolchainSmokeTest {
    @Test
    @DisplayName("tests run on the pinned Java 25 toolchain")
    void runsOnPinnedToolchain() {
        assertThat(Runtime.version().feature()).isEqualTo(25);
    }
}
