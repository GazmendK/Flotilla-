/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SimulationIsolationTest {
    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("dev.flotilla");

    @Test
    @DisplayName("the simulation depends on the consensus core only")
    void simulationTouchesOnlyTheCore() {
        noClasses()
                .that()
                .resideInAPackage("dev.flotilla.sim..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "dev.flotilla.server..",
                        "dev.flotilla.storage..",
                        "dev.flotilla.transport..",
                        "dev.flotilla.client..",
                        "dev.flotilla.cli..",
                        "dev.flotilla.it..",
                        "dev.flotilla.bench..")
                .because("the simulation implements the same ports as the production runtime. "
                        + "Depending on the runtime instead would mean it is testing an "
                        + "integration, not the algorithm")
                .allowEmptyShould(true)
                .check(CLASSES);
    }

    @Test
    @DisplayName("the simulation reads no clock of its own")
    void simulationUsesVirtualTimeOnly() {
        noClasses()
                .that()
                .resideInAPackage("dev.flotilla.sim..")
                .should()
                .callMethod(System.class, "currentTimeMillis")
                .orShould()
                .callMethod(System.class, "nanoTime")
                .orShould()
                .callMethod(Math.class, "random")
                .because("virtual time is the point: a simulated hour must take milliseconds, and "
                        + "the same seed must always produce the same run")
                .allowEmptyShould(true)
                .check(CLASSES);
    }
}
