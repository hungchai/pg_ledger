FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY settings.gradle.kts build.gradle.kts ./
COPY gradlew ./
COPY gradle/wrapper/ gradle/wrapper/
RUN chmod +x gradlew
COPY pgledger-core pgledger-core
COPY pgledger-client-sdk pgledger-client-sdk
COPY pgledger-restful pgledger-restful
COPY db db
# Wrapper downloads Gradle 9.6 on first run; version is pinned by gradle-wrapper.properties.
RUN ./gradlew :pgledger-restful:bootJar --no-daemon

FROM eclipse-temurin:21-jre
WORKDIR /opt/pgledger
COPY --from=build /src/pgledger-restful/build/libs/pgledger-restful-*.jar /opt/pgledger/app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/opt/pgledger/app.jar"]
