plugins {
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.node.gradle)
}

// ── Admin Console (ADR-012) — zt-admin-ui/ is a plain npm project, not a
// Gradle subproject (it has no Java sources; making it one would drag the
// root build.gradle.kts's unconditional Java/Spring-BOM `subprojects{}`
// config onto it for no benefit). The node-gradle plugin drives it directly
// from here instead.
node {
    version.set("20.11.0")
    download.set(false) // use whatever node/npm is already on PATH
    nodeProjectDir.set(file("${rootDir}/zt-admin-ui"))
}

val buildAdminUi by tasks.registering(com.github.gradle.node.npm.task.NpmTask::class) {
    description = "Builds the React Admin Console (zt-admin-ui) via npm run build"
    dependsOn(tasks.named("npmInstall"))
    args.set(listOf("run", "build"))
    inputs.dir(file("${rootDir}/zt-admin-ui/src"))
    inputs.file(file("${rootDir}/zt-admin-ui/package.json"))
    inputs.file(file("${rootDir}/zt-admin-ui/package-lock.json"))
    outputs.dir(file("${rootDir}/zt-admin-ui/dist"))
}

tasks.named<ProcessResources>("processResources") {
    dependsOn(buildAdminUi)
    from(file("${rootDir}/zt-admin-ui/dist")) {
        into("static/admin")
    }
}

// ── Integration-test source set ───────────────────────────────────────────────
// src/it/java + src/it/resources run in the `integrationTest` task.
// Inherits all testImplementation/testRuntimeOnly deps from the unit-test scope.
sourceSets {
    create("it") {
        compileClasspath += sourceSets.main.get().output + sourceSets.test.get().output
        runtimeClasspath += sourceSets.main.get().output + sourceSets.test.get().output
        // Make keycloak/realm-export.json available as a classpath resource
        resources.srcDir(rootDir.resolve("keycloak"))
    }
}

val itImplementation: Configuration by configurations.getting {
    extendsFrom(configurations.testImplementation.get())
}
@Suppress("UNUSED_VARIABLE")
val itRuntimeOnly: Configuration by configurations.getting {
    extendsFrom(configurations.testRuntimeOnly.get())
}

dependencies {
    // Internal library
    implementation(project(":auth-library"))

    // Gateway & Security
    implementation(libs.spring.cloud.gateway)
    implementation(libs.spring.oauth2.resource)
    implementation(libs.spring.actuator)

    // Database — JDBC DataSource (Flyway migrations only; ADR-012 retired the
    // R2DBC-backed access_policies runtime query path — users2service is YAML-only now)
    implementation(libs.spring.jdbc)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgres)
    runtimeOnly(libs.postgresql.driver)

    // mTLS outbound — Netty SslContext (version from Spring Boot BOM)
    implementation(libs.netty.handler)

    // YAML policy definition loader (Stage 10 / ADR-011)
    implementation(libs.jackson.dataformat.yaml)

    // OBO token creation — JJWT (api on compile path; impl+jackson at runtime via auth-library)
    implementation(libs.jjwt.api)

    // Unit test
    testImplementation(libs.spring.test)
    testImplementation(libs.spring.security.test)
    testImplementation(libs.reactor.test)

    // Integration-test — Testcontainers + WireMock + RestAssured
    itImplementation(libs.testcontainers.junit5)
    itImplementation(libs.testcontainers.postgresql)
    itImplementation(libs.keycloak.testcontainers)
    itImplementation(libs.rest.assured)
    itImplementation(libs.wiremock.standalone)
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("gateway-service.jar")
}

tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    workingDir = rootProject.projectDir
}

// ── integrationTest task ──────────────────────────────────────────────────────
tasks.register<Test>("integrationTest") {
    description = "Runs E2E integration tests (Testcontainers: Postgres + Keycloak; WireMock for downstream)"
    group       = "verification"
    testClassesDirs = sourceSets["it"].output.classesDirs
    classpath       = sourceSets["it"].runtimeClasspath
    useJUnitPlatform()
    shouldRunAfter("test")

    // Docker Desktop on WSL2 only accepts API ≥ v1.44 on /var/run/docker.sock.
    // docker-java reads the API version from the JVM system property "docker.api.version"
    // (not the env var DOCKER_API_VERSION which belongs to the Docker CLI).
    // EnvironmentAndSystemPropertyClientProviderStrategy is forced via testcontainers.properties
    // so that docker-java honours the system property (UnixSocketClientProviderStrategy
    // hardcodes VERSION_1_19 and ignores it).
    environment("DOCKER_HOST", "unix:///var/run/docker.sock")
    // "api.version" is the actual property key read by docker-java's shaded
    // DefaultDockerClientConfig.overrideDockerPropertiesWithSystemProperties().
    // Also set in BaseZteIntegrationTest static block as belt-and-suspenders.
    jvmArgs("-Dapi.version=1.45")

    // Print test events to console for CI visibility
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = false
    }
}
