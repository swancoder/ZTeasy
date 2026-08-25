# ── Build stage ──────────────────────────────────────────────
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app

COPY gradlew .
COPY gradle gradle
COPY settings.gradle.kts .
COPY build.gradle.kts .
COPY gradle/libs.versions.toml gradle/

COPY auth-library/build.gradle.kts auth-library/
COPY zt-agents/build.gradle.kts zt-agents/
COPY auth-library/src auth-library/src
COPY zt-agents/src zt-agents/src

RUN chmod +x gradlew && ./gradlew :zt-agents:bootJar --no-daemon -x test

# ── Runtime stage ─────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/zt-agents/build/libs/*.jar app.jar
EXPOSE 8083
ENTRYPOINT ["java", "-jar", "app.jar"]
