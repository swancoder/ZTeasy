plugins {
    alias(libs.plugins.spring.boot)
}

dependencies {
    // ADR-037: secrets have no committed defaults any more, so a local run reads
    // them from the gitignored .env in the repo root — the same mechanism ADR-008
    // chose for zt-agents, and the same file docker compose substitutes from.
    implementation(libs.spring.dotenv)

    // Internal library — provides UserContextTokenService, ReloadableSslContextFactory
    implementation(project(":auth-library"))

    // WebFlux: reactive HTTP server (Netty) + WebClient for outbound mTLS calls
    implementation(libs.spring.webflux)
    implementation(libs.spring.actuator)

    // OpenAPI schema at /v3/api-docs (+ Swagger UI) — ADR-016 amendment: lets
    // the gateway's AutoDiscoveryWorker confirm this service on registration
    implementation(libs.springdoc.openapi.webflux.ui)

    // mTLS outbound WebClient — Netty SslContext (version from Spring Boot BOM)
    implementation(libs.netty.handler)

    // OBO token validation
    implementation(libs.jjwt.api)

    testImplementation(libs.spring.test)
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("service-a.jar")
}

tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    workingDir = rootProject.projectDir
}
