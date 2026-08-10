plugins {
    `kotlin-dsl`
}

dependencies {
    // Plugin marker artifacts: this is what puts third-party plugins on the classpath of the
    // precompiled script plugins in src/main/kotlin.
    implementation(libs.build.spotless)
    implementation(libs.build.errorprone)
}

kotlin {
    // The Kotlin DSL compiles against the JVM target of the daemon; pinning it keeps
    // build-logic reproducible across machines.
    jvmToolchain(25)
}
