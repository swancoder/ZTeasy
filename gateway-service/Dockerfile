# ── UI build stage (ADR-027) ─────────────────────────────────
# Both SPAs are built here with plain npm; the Gradle stage below then skips
# its own buildAdminUi/buildApproverUi tasks (which need npm on PATH) and
# packages the ready-made dist/ dirs via processResources' from(...) copy.
FROM node:20-alpine AS ui-build
WORKDIR /app

COPY zt-admin-ui/package.json zt-admin-ui/package-lock.json zt-admin-ui/
RUN cd zt-admin-ui && npm ci
COPY zt-admin-ui zt-admin-ui
RUN cd zt-admin-ui && npm run build

COPY zt-approver-ui/package.json zt-approver-ui/
RUN cd zt-approver-ui && npm install
COPY zt-approver-ui zt-approver-ui
RUN cd zt-approver-ui && npm run build

# ── Gradle build stage ───────────────────────────────────────
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app

COPY gradlew .
COPY gradle gradle
COPY settings.gradle.kts .
COPY build.gradle.kts .
COPY gradle/libs.versions.toml gradle/

COPY auth-library/build.gradle.kts auth-library/
COPY gateway-service/build.gradle.kts gateway-service/
COPY auth-library/src auth-library/src
COPY gateway-service/src gateway-service/src

# The pre-built SPA bundles, exactly where processResources expects them
COPY --from=ui-build /app/zt-admin-ui/dist zt-admin-ui/dist
COPY --from=ui-build /app/zt-approver-ui/dist zt-approver-ui/dist

RUN chmod +x gradlew && ./gradlew :gateway-service:bootJar --no-daemon -x test \
      -x npmInstall -x buildAdminUi -x npmInstallApproverUi -x buildApproverUi

# ── Runtime stage ─────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/gateway-service/build/libs/gateway-service.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
