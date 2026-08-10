import com.diffplug.spotless.LineEnding
import net.ltgt.gradle.errorprone.errorprone
import org.gradle.api.artifacts.VersionCatalogsExtension

/*
 * Static analysis and formatting. Both are wired as build failures rather than reports:
 * a gate that can be ignored is not a gate.
 */

plugins {
    java
    id("com.diffplug.spotless")
    id("net.ltgt.errorprone")
}

val libs = the<VersionCatalogsExtension>().named("libs")

dependencies {
    errorprone(libs.findLibrary("errorprone-core").get())
    errorprone(libs.findLibrary("nullaway").get())
}

tasks.withType<JavaCompile>().configureEach {
    options.errorprone {
        disableWarningsInGeneratedCode = true

        // Everything under dev.flotilla is @NullMarked (JSpecify), which makes every reference
        // non-null unless annotated otherwise. NullAway then turns "this could be null" from a
        // runtime NPE into a compile error.
        error("NullAway")
        option("NullAway:AnnotatedPackages", "dev.flotilla")
        option("NullAway:JSpecifyMode", "true")
    }
}

spotless {
    // LF everywhere, on every platform. Combined with .gitattributes this keeps diffs free of
    // whitespace-only noise when the project is built on Windows and on Linux CI.
    lineEndings = LineEnding.UNIX

    java {
        target("src/*/java/**/*.java")
        palantirJavaFormat(libs.findVersion("palantirJavaFormat").get().requiredVersion)
        removeUnusedImports()
        licenseHeaderFile(rootDir.resolve("gradle/spotless/license-header.txt"))
        trimTrailingWhitespace()
        endWithNewline()
    }
}
