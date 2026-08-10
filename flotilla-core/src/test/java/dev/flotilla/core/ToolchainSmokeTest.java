/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that tests execute on the pinned toolchain JDK rather than on whichever JVM happens to
 * run the Gradle daemon.
 *
 * <p>This is not ceremony: the Gradle daemon, the compiler and the test JVM are configured in
 * three different places, and a mismatch shows up much later as a confusing error in a formatter
 * or an analyzer. Failing here is cheaper.
 */
class ToolchainSmokeTest {

    @Test
    @DisplayName("tests run on the pinned Java 25 toolchain")
    void runsOnPinnedToolchain() {
        assertThat(Runtime.version().feature()).isEqualTo(25);
    }
}
