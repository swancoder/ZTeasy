# ── Build stage ──────────────────────────────────────────────
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app

# Copy Gradle wrapper + build files first (layer cache)
COPY gradlew .
COPY gradle gradle
COPY settings.gradle.kts .
COPY build.gradle.kts .
COPY gradle/libs.versions.toml gradle/

# Copy only the modules needed for :service-a build
COPY auth-library/build.gradle.kts auth-library/
COPY gateway-service/build.gradle.kts gateway-service/
COPY service-a/build.gradle.kts service-a/

# Copy source
COPY auth-library/src auth-library/src
COPY service-a/src service-a/src

RUN chmod +x gradlew && ./gradlew :service-a:bootJar --no-daemon -x test

# ── Runtime stage ─────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/service-a/build/libs/service-a.jar app.jar
EXPOSE 8081 9081
ENTRYPOINT ["java", "-jar", "app.jar"]
