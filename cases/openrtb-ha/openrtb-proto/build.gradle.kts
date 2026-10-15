plugins {
    id("com.google.protobuf")
}

val protobufVersion = rootProject.extra["protobufVersion"] as String

dependencies {
    api("com.google.protobuf:protobuf-java:$protobufVersion")
}

protobuf {
    // Official protoc binary from Maven Central, resolved for the host OS and architecture.
    protoc {
        artifact = "com.google.protobuf:protoc:$protobufVersion"
    }
}

sourceSets {
    main {
        // The vendored IAB proto lives at the case root, shared by all modules.
        proto {
            srcDir("../proto/src/main")
        }
    }
}
