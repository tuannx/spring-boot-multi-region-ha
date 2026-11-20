plugins {
    java
    application
}

group = "com.multiregion"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

application {
    mainClass.set("com.multiregion.xds.XdsControlPlaneApplication")
}

repositories {
    mavenCentral()
}

val controlPlaneVersion = "1.0.55"
val grpcVersion = "1.85.1"
val slf4jVersion = "2.0.16"

dependencies {
    implementation("io.envoyproxy.controlplane:server:$controlPlaneVersion")
    implementation("io.grpc:grpc-netty-shaded:$grpcVersion")
    implementation("org.slf4j:slf4j-api:$slf4jVersion")
    implementation("org.slf4j:slf4j-simple:$slf4jVersion")

    testImplementation("io.grpc:grpc-xds:$grpcVersion")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

tasks.withType<JavaCompile> {
    options.compilerArgs.add("-parameters")
    options.compilerArgs.add("-Xlint:unchecked")
    options.compilerArgs.add("-Xlint:deprecation")
}

tasks.register("printTestClasspath") {
    doLast { configurations["testRuntimeClasspath"].files.forEach { println(it) } }
}
