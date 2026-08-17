import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    java
}

val libs = the<VersionCatalogsExtension>().named("libs")

dependencies {
    testImplementation(platform(libs.findLibrary("junit-bom").get()))
    testImplementation(libs.findLibrary("junit-jupiter").get())
    testImplementation(libs.findLibrary("assertj-core").get())

    testImplementation(libs.findLibrary("archunit").get())

    testImplementation(libs.findLibrary("jqwik").get())
    testRuntimeOnly(libs.findLibrary("junit-platform-launcher").get())
}

val forwardedProperties = listOf(
    "flotilla.sim.seed",
    "flotilla.sim.seeds",
    "flotilla.sim.ticks",
    "flotilla.sim.offset",
)

tasks.withType<Test>().configureEach {
    useJUnitPlatform()

    forwardedProperties.forEach { key ->
        providers.systemProperty(key).orNull?.let { systemProperty(key, it) }
    }

    failOnNoDiscoveredTests = false

    testLogging {
        events("failed", "skipped")
        exceptionFormat = TestExceptionFormat.FULL
        showStackTraces = true
        showCauses = true
    }
}
