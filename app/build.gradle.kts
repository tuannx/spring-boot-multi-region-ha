plugins {
    id("org.springframework.boot") version "3.4.3"
    id("io.spring.dependency-management") version "1.1.7"
    java
}

group = "com.multiregion"
version = "0.0.1-SNAPSHOT"
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // Spring Boot
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-amqp")

    // PostgreSQL driver
    implementation("org.postgresql:postgresql:42.7.8")

    // AWS Advanced JDBC Wrapper with Global Database failover
    implementation("software.amazon.jdbc:aws-advanced-jdbc-wrapper:4.0.1")

    // Spring Retry for HA/DR failover resilience
    implementation("org.springframework.retry:spring-retry")
    implementation("org.springframework.boot:spring-boot-starter-aop")

    // Testing
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("com.tngtech.archunit:archunit-junit5:1.4.2")
    testRuntimeOnly("com.h2database:h2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

tasks.withType<JavaCompile> {
    options.compilerArgs.add("-Xlint:unchecked")
    options.compilerArgs.add("-Xlint:deprecation")
}
