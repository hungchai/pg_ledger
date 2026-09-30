plugins {
    java
    id("org.springframework.boot") version "3.4.5"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "io.zodia"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":pgledger-core"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.apache.shardingsphere:shardingsphere-jdbc:5.5.2")
    implementation("org.mybatis.spring.boot:mybatis-spring-boot-starter:3.0.4")
    implementation("com.zaxxer:HikariCP")
    runtimeOnly("org.postgresql:postgresql")
    testImplementation(project(":pgledger-client-sdk"))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.postgresql:postgresql")
}

tasks.withType<JavaCompile> {
    options.compilerArgs.add("-parameters")
}

tasks.test {
    useJUnitPlatform {
        excludeTags("stress")
    }
}

tasks.register<Test>("stressTest") {
    description = "Writer/reader HTTP stress. Requires docker compose up."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform {
        includeTags("stress")
    }
    outputs.upToDateWhen { false }
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
