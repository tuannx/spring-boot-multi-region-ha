plugins {
    id("java")
}

val protobufVersion = rootProject.extra["protobufVersion"] as String
val hdrhistogramVersion = rootProject.extra["hdrhistogramVersion"] as String

dependencies {
    implementation(project(":openrtb-proto"))
    implementation("com.google.protobuf:protobuf-java-util:$protobufVersion")
    implementation("org.hdrhistogram:HdrHistogram:$hdrhistogramVersion")
}

// Executable fat jar: java -jar loadgen/build/libs/loadgen-0.1.0-SNAPSHOT.jar
tasks.jar {
    manifest {
        attributes["Main-Class"] = "com.example.openrtb.loadgen.LoadGenerator"
    }
    from(configurations.runtimeClasspath.map { files -> files.map { if (it.isDirectory) it else zipTree(it) } })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
