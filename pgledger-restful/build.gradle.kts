plugins {
    java
    application
}

group = "io.zodia"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

application {
    mainClass.set("io.zodia.pgledger.rest.PgLedgerServerMain")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":pgledger-core"))
    testImplementation(project(":pgledger-client-sdk"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("org.postgresql:postgresql:42.7.7")
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
