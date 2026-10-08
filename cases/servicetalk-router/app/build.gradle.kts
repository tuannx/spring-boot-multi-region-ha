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
    mainClass.set("com.multiregion.router.ServiceTalkRouterApplication")
}

repositories {
    mavenCentral()
}

val servicetalkVersion = "0.42.57"
val slf4jVersion = "2.0.16"

dependencies {
    implementation("io.servicetalk:servicetalk-http-netty:$servicetalkVersion")
    implementation("io.servicetalk:servicetalk-loadbalancer:$servicetalkVersion")
    implementation("io.servicetalk:servicetalk-dns-discovery-netty:$servicetalkVersion")
    implementation("org.slf4j:slf4j-api:$slf4jVersion")
    implementation("org.slf4j:slf4j-simple:$slf4jVersion")

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
