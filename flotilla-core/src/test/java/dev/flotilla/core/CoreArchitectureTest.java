/*
 * Copyright 2026 The Flotilla Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.flotilla.core;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CoreArchitectureTest {
    private static final JavaClasses CORE = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("dev.flotilla.core");

    @Test
    @DisplayName("the core performs no I/O")
    void performsNoIo() {
        noClasses()
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("java.io..", "java.nio.file..", "java.net..", "javax.net..")
                .because("the core describes effects in a Ready object and never performs them; "
                        + "that separation is what allows the simulation to run a whole cluster "
                        + "in one thread with no files and no sockets")
                .check(CORE);
    }

    @Test
    @DisplayName("the core starts no threads and uses no concurrency primitives")
    void usesNoConcurrency() {
        noClasses()
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("java.util.concurrent..")
                .orShould()
                .dependOnClassesThat()
                .haveFullyQualifiedName(Thread.class.getName())
                .because("the core is entered from exactly one thread, so it needs no "
                        + "synchronization; introducing any here would mean the single-writer "
                        + "guarantee has been broken somewhere in the runtime")
                .check(CORE);
    }

    @Test
    @DisplayName("the core never reads a clock")
    void readsNoClock() {
        noClasses()
                .should()
                .callMethod(System.class, "currentTimeMillis")
                .orShould()
                .callMethod(System.class, "nanoTime")
                .orShould()
                .dependOnClassesThat()
                .resideInAnyPackage("java.time..")
                .because("time enters the core as logical ticks. Wall-clock time would make the "
                        + "same input produce different behaviour on a slow machine, and would "
                        + "make election timeouts depend on the whims of NTP")
                .check(CORE);
    }

    @Test
    @DisplayName("the core uses no unseeded randomness")
    void usesNoUnseededRandomness() {
        noClasses()
                .should()
                .callMethod(Math.class, "random")
                .orShould()
                .callMethod(UUID.class, "randomUUID")
                .orShould()
                .dependOnClassesThat()
                .haveFullyQualifiedName("java.security.SecureRandom")
                .because("randomized election timeouts must come from the injected RandomSource, "
                        + "or a failure found after ten million simulated events could never be "
                        + "replayed")
                .check(CORE);
    }

    @Test
    @DisplayName("the core uses no hash-ordered collections")
    void usesNoHashOrderedCollections() {
        noClasses()
                .should()
                .dependOnClassesThat()
                .haveFullyQualifiedName("java.util.HashMap")
                .orShould()
                .dependOnClassesThat()
                .haveFullyQualifiedName("java.util.HashSet")
                .because("iteration order of a hash container is unspecified and, for Set.of and "
                        + "Set.copyOf, deliberately randomized per JVM run. Iterating peers in "
                        + "such an order makes two runs of the same seed diverge. Use TreeMap, "
                        + "TreeSet or LinkedHashMap")
                .check(CORE);
    }

    @Test
    @DisplayName("nothing in the core is mutable state hidden in a field")
    void hasOnlyFinalFields() {
        fields().that()
                .areDeclaredInClassesThat()
                .resideInAPackage("dev.flotilla.core.message..")
                .should()
                .beFinal()
                .because("messages cross the boundary between network threads and the event loop; "
                        + "immutability is what makes that publication safe without any locking")
                .check(CORE);
    }

    @Test
    @DisplayName("the core depends on no third-party library")
    void hasNoThirdPartyDependencies() {
        noClasses()
                .should()
                .dependOnClassesThat()
                .resideOutsideOfPackages("dev.flotilla..", "java..", "javax..", "org.jspecify..")
                .because("the core must stay embeddable and free of transitive baggage; a "
                        + "dependency here would end up on the classpath of everything that ever "
                        + "uses the consensus module")
                .check(CORE);
    }
}
