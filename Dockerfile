FROM gradle:8.10-jdk21 AS build
WORKDIR /src
COPY settings.gradle.kts build.gradle.kts ./
COPY pgledger-core pgledger-core
COPY pgledger-client-sdk pgledger-client-sdk
COPY pgledger-restful pgledger-restful
COPY db db
RUN gradle :pgledger-restful:bootJar --no-daemon

FROM eclipse-temurin:21-jre
WORKDIR /opt/pgledger
COPY --from=build /src/pgledger-restful/build/libs/pgledger-restful-*.jar /opt/pgledger/app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/opt/pgledger/app.jar"]
