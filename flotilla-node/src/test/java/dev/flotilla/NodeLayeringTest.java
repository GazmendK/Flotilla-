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

class NodeLayeringTest {
    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("dev.flotilla");

    @Test
    @DisplayName("storage knows nothing about who replicates the log")
    void storageIsALeaf() {
        noClasses()
                .that()
                .resideInAPackage("dev.flotilla.storage..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "dev.flotilla.server..",
                        "dev.flotilla.client..",
                        "dev.flotilla.cli..",
                        "dev.flotilla.transport..",
                        "dev.flotilla.kv..")
                .because("the write-ahead log stores entries; it has no business knowing what "
                        + "they mean or who asked for them")
                .allowEmptyShould(true)
                .check(CLASSES);
    }

    @Test
    @DisplayName("the state machine knows nothing about consensus plumbing")
    void stateMachineIsALeaf() {
        noClasses()
                .that()
                .resideInAPackage("dev.flotilla.kv..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "dev.flotilla.server..",
                        "dev.flotilla.client..",
                        "dev.flotilla.cli..",
                        "dev.flotilla.transport..",
                        "dev.flotilla.storage..")
                .because("the state machine applies committed commands deterministically; if it "
                        + "can reach the transport or the disk it can also become nondeterministic")
                .allowEmptyShould(true)
                .check(CLASSES);
    }

    @Test
    @DisplayName("the transport maps messages and drives nothing")
    void transportIsALeaf() {
        noClasses()
                .that()
                .resideInAPackage("dev.flotilla.transport..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("dev.flotilla.server..", "dev.flotilla.cli..", "dev.flotilla.storage..")
                .because("the transport is an adapter: it converts between wire types and domain "
                        + "types. Logic that leaks in here is logic the simulation cannot reach")
                .allowEmptyShould(true)
                .check(CLASSES);
    }

    @Test
    @DisplayName("nothing depends on the command line")
    void cliIsTheOutermostLayer() {
        noClasses()
                .that()
                .resideOutsideOfPackage("dev.flotilla.cli..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("dev.flotilla.cli..")
                .because("the CLI is one of several possible front ends; anything that depends on "
                        + "it can no longer be embedded")
                .allowEmptyShould(true)
                .check(CLASSES);
    }
}
