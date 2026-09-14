plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(libs.build.spotless)
    implementation(libs.build.errorprone)
    implementation(libs.build.protobuf)
}

kotlin {
    jvmToolchain(25)
}
