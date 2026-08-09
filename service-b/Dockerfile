# ── Stage 1: Build ────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /workspace

# Copy Gradle wrapper and version catalog first for layer caching
COPY gradle/            gradle/
COPY gradlew            .
COPY build.gradle.kts   .
COPY settings.gradle.kts .

# Copy all sub-project build files (needed for dependency resolution)
COPY gateway-service/build.gradle.kts  gateway-service/
COPY auth-library/build.gradle.kts     auth-library/
COPY service-a/build.gradle.kts        service-a/
COPY service-b/build.gradle.kts        service-b/

# Download dependencies
RUN ./gradlew :service-b:dependencies --no-daemon -q || true

# Copy source code
COPY auth-library/src/   auth-library/src/
COPY service-b/src/      service-b/src/

# Build the fat JAR (skip tests — run separately in CI)
RUN ./gradlew :service-b:bootJar --no-daemon -x test

# ── Stage 2: Runtime ──────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine AS runtime

# Non-root user for security
RUN addgroup -S zte && adduser -S -G zte zte
USER zte

WORKDIR /app
COPY --from=builder /workspace/service-b/build/libs/service-b.jar app.jar

# Certs are bind-mounted at runtime: -v ./certs:/app/certs:ro
# Configure via ZTE_CERTS_DIR=/app/certs

EXPOSE 8082 9082
ENTRYPOINT ["java", "-jar", "app.jar"]
