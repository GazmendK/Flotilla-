import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    java
    id("com.google.protobuf")
}

val libs = the<VersionCatalogsExtension>().named("libs")

sourceSets.named("main") {
    extensions.getByName<SourceDirectorySet>("proto").srcDir(rootDir.resolve("proto"))
}

protobuf {
    protoc {
        artifact = libs.findLibrary("protobuf-protoc").get().get().toString()
    }
    plugins {
        create("grpc") {
            artifact = libs.findLibrary("grpc-protoc-gen").get().get().toString()
        }
    }
    generateProtoTasks {
        all().configureEach {
            plugins {
                create("grpc")
            }
        }
    }
}
