import com.diffplug.spotless.LineEnding
import net.ltgt.gradle.errorprone.errorprone
import org.gradle.api.artifacts.VersionCatalogsExtension

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

        error("NullAway")
        option("NullAway:AnnotatedPackages", "dev.flotilla")
        option("NullAway:JSpecifyMode", "true")
    }
}

spotless {
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
