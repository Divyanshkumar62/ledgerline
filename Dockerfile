# Multi-stage build: compile with a JDK, run on a slim JRE as a non-root user.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
# Wrapper + build files first: this layer (and the dependency download) is cached until they change.
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
RUN chmod +x gradlew && ./gradlew --no-daemon --quiet dependencies > /dev/null
COPY config config
COPY src src
# Tests need Docker (Testcontainers) and run in CI / `./gradlew check`, not inside the image build.
RUN ./gradlew --no-daemon --quiet bootJar

FROM eclipse-temurin:21-jre
RUN useradd --uid 10001 --no-create-home --shell /usr/sbin/nologin ledgerline
WORKDIR /app
COPY --from=build /src/build/libs/ledgerline.jar app.jar
USER ledgerline
EXPOSE 8080
HEALTHCHECK --interval=5s --timeout=3s --start-period=30s --retries=12 \
  CMD curl -fsS http://localhost:8080/actuator/health/readiness || exit 1
# Size the heap from the container's memory limit, not the host's.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
