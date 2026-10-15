plugins {
    id("org.springframework.boot") version "4.1.1" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
    id("com.google.protobuf") version "0.9.4" apply false
}

// Must match protoc (com.google.protobuf:protoc) and protobuf-java. Edition 2023 needs protoc >= 27.
extra["protobufVersion"] = "4.35.1"
extra["hdrhistogramVersion"] = "2.2.2"

allprojects {
    group = "com.example.openrtb"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "java-library")

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion = JavaLanguageVersion.of(26)
        }
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }

}
