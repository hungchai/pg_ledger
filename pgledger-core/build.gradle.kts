plugins {
    java
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
    implementation("org.postgresql:postgresql:42.7.7")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.18.2")
    implementation("org.mybatis:mybatis:3.5.19")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation(platform("org.testcontainers:testcontainers-bom:1.20.4"))
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.16")
}

// SQL lives in /db at the repo root, not under this module.
tasks.processResources {
    from(rootProject.file("db")) {
        into("db")
    }
}

tasks.withType<JavaCompile> {
    options.compilerArgs.add("-parameters")
}

tasks.test {
    useJUnitPlatform()
    workingDir = rootProject.projectDir
    // Docker Desktop on macOS often exposes the engine on ~/.docker/run/docker.sock.
    val dockerSock = System.getenv("DOCKER_HOST")
            ?: "unix://${System.getProperty("user.home")}/.docker/run/docker.sock"
    environment("DOCKER_HOST", dockerSock)
    environment("TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE",
            System.getenv("TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE")
                    ?: "${System.getProperty("user.home")}/.docker/run/docker.sock")
}

tasks.register("printTestClasspath") {
    doLast {
        println(configurations.getByName("testRuntimeClasspath").asPath)
    }
}
