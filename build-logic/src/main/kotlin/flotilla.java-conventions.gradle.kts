import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    `java-library`
}

val libs = the<VersionCatalogsExtension>().named("libs")

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(libs.findVersion("java").get().requiredVersion)
    }
    withSourcesJar()
}

dependencies {
    compileOnly(libs.findLibrary("jspecify").get())
    testCompileOnly(libs.findLibrary("jspecify").get())
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(
        listOf(
            "-Xlint:all,-serial,-this-escape,-processing",

            "-Werror",
        ),
    )
}

tasks.withType<Javadoc>().configureEach {
    (options as StandardJavadocDocletOptions).apply {
        encoding = "UTF-8"

        addBooleanOption("Xdoclint:all,-missing", true)
        addStringOption("Xwerror", "-quiet")
    }

    onlyIf("module declares at least one type to document") { task ->
        (task as Javadoc).source.files.any { file -> file.name != "package-info.java" }
    }
}

tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    dirPermissions { unix("rwxr-xr-x") }
    filePermissions { unix("rw-r--r--") }
}
