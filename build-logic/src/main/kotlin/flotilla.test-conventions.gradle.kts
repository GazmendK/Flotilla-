import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.tasks.testing.logging.TestExceptionFormat

/*
 * Test baseline: JUnit Jupiter plus AssertJ. No mocking framework by design -- see
 * docs/adr/0007-no-mocking-policy.md. Tests use real in-memory implementations of the same
 * ports the production code uses, so the simulation exercises the shipped code path.
 */

plugins {
    java
}

val libs = the<VersionCatalogsExtension>().named("libs")

dependencies {
    testImplementation(platform(libs.findLibrary("junit-bom").get()))
    testImplementation(libs.findLibrary("junit-jupiter").get())
    testImplementation(libs.findLibrary("assertj-core").get())
    testRuntimeOnly(libs.findLibrary("junit-platform-launcher").get())
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()

    // Modules are added to the build before they have tests; an empty module is not a failure.
    failOnNoDiscoveredTests = false

    testLogging {
        events("failed", "skipped")
        exceptionFormat = TestExceptionFormat.FULL
        showStackTraces = true
        showCauses = true
    }
}
