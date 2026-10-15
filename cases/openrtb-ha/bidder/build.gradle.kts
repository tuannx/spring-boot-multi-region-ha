plugins {
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

val protobufVersion = rootProject.extra["protobufVersion"] as String

dependencies {
    implementation(project(":openrtb-proto"))
    implementation("com.google.protobuf:protobuf-java-util:$protobufVersion")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("io.micrometer:micrometer-registry-prometheus")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
