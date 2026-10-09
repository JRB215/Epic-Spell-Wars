# The jar is built and tested beforehand by `./gradlew build` (the GitHub workflows do this first).
# This image only wraps the finished jar, so it is small and quick to build.
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY esw-server/build/libs/app.jar app.jar
# Card pictures and the leaderboard live in the mounted folder, so art can change without a rebuild.
ARG GIT_SHA=unknown
ENV PORT=80 ESW_ASSETS=/custom-assets ESW_VERSION=${GIT_SHA}
VOLUME /custom-assets
EXPOSE 80
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
