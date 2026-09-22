plugins {
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
    id("org.pkl-lang") version "0.32.1"
    java
}

group = "com.multiregion"
version = "0.0.1-SNAPSHOT"
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(26)
    }
}

springBoot {
    buildInfo()
}

repositories {
    mavenCentral()
}

dependencies {
    // Spring Boot
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-amqp")

    // Apple Pkl: typed, validated failover configuration.
    implementation("org.pkl-lang:pkl-spring:0.18.0")

    // PostgreSQL driver
    implementation("org.postgresql:postgresql:42.7.13")

    // AWS Advanced JDBC Wrapper with Global Database failover
    implementation("software.amazon.jdbc:aws-advanced-jdbc-wrapper:4.4.0")

    // Testing
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("com.tngtech.archunit:archunit-junit5:1.5.0")
    testImplementation("org.quickperf:quick-perf-junit5:1.1.0")
    testImplementation("org.quickperf:quick-perf-sql-annotations:1.1.0")
    testRuntimeOnly("com.h2database:h2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

pkl {
    javaCodeGenerators {
        register("configClasses") {
            generateGetters.set(true)
            generateSpringBootConfig.set(true)
            sourceModules.set(files("src/main/resources/pkl/PklApplicationConfig.pkl"))
        }
    }
}

// Gradle 9.7 validates task inputs strictly. The Pkl Spring Boot generator
// reads build-info output, so make that dependency explicit.
tasks.named("configClasses") {
    dependsOn("bootBuildInfo")
}
tasks.named("configClassesGatherImports") {
    dependsOn("bootBuildInfo")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

tasks.withType<JavaCompile> {
    options.compilerArgs.add("-parameters")
    options.compilerArgs.add("-Xlint:unchecked")
    options.compilerArgs.add("-Xlint:deprecation")
}
