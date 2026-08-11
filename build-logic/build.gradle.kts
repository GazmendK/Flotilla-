plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(libs.build.spotless)
    implementation(libs.build.errorprone)
}

kotlin {
    jvmToolchain(25)
}
