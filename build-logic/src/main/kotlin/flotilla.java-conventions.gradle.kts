import org.gradle.api.artifacts.VersionCatalogsExtension

/*
 * Baseline for every Java module: toolchain, language level, warnings-as-errors and
 * reproducible archives. Deliberately contains no dependencies beyond nullness annotations.
 */

plugins {
    `java-library`
}

val libs = the<VersionCatalogsExtension>().named("libs")

java {
    toolchain {
        // Pinned via the version catalog so the toolchain, the daemon JVM and CI cannot drift apart.
        languageVersion = JavaLanguageVersion.of(libs.findVersion("java").get().requiredVersion)
    }
    withSourcesJar()
}

dependencies {
    // JSpecify carries no runtime behaviour, so it is compile-time only. This is what keeps
    // flotilla-core free of runtime dependencies while still being null-checked.
    compileOnly(libs.findLibrary("jspecify").get())
    testCompileOnly(libs.findLibrary("jspecify").get())
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(
        listOf(
            // -serial: we never rely on Java serialization.
            // -this-escape: fires on legitimate constructor patterns and is too noisy to be useful.
            // -processing: Error Prone and the picocli processor trigger it by design.
            "-Xlint:all,-serial,-this-escape,-processing",
            // A warning nobody fixes is a warning nobody reads.
            "-Werror",
        ),
    )
}

tasks.withType<Javadoc>().configureEach {
    (options as StandardJavadocDocletOptions).apply {
        encoding = "UTF-8"
        // Broken links and malformed HTML fail the build; missing comments do not (yet).
        addBooleanOption("Xdoclint:all,-missing", true)
        addStringOption("Xwerror", "-quiet")
    }
}

tasks.withType<AbstractArchiveTask>().configureEach {
    // Reproducible builds: identical sources must produce byte-identical archives, otherwise
    // release artifacts cannot be verified independently.
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    dirPermissions { unix("rwxr-xr-x") }
    filePermissions { unix("rw-r--r--") }
}
