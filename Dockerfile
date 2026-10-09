# Stage 1: build the server jar (tests are run separately by the build workflow).
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle gradle
COPY esw-model esw-model
COPY esw-engine esw-engine
COPY esw-server esw-server
COPY Base/cards Base/cards
RUN chmod +x gradlew && ./gradlew :esw-server:bootJar --no-daemon -x test

# Stage 2: a small image that only runs the jar.
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/esw-server/build/libs/app.jar app.jar
# Card pictures and the leaderboard live in the mounted folder, so art can change without a rebuild.
ENV PORT=80 ESW_ASSETS=/custom-assets
VOLUME /custom-assets
EXPOSE 80
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
