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

// ── Approval Center (ADR-026) — a second, independent npm project
// (zt-approver-ui/), same pattern as zt-admin-ui above. The node {} extension
// only points at one project dir, so these tasks override workingDir
// explicitly instead of relying on the extension default.
val npmInstallApproverUi by tasks.registering(com.github.gradle.node.npm.task.NpmTask::class) {
    description = "Installs zt-approver-ui npm dependencies"
    workingDir.set(file("${rootDir}/zt-approver-ui"))
    args.set(listOf("install"))
    inputs.file(file("${rootDir}/zt-approver-ui/package.json"))
    outputs.dir(file("${rootDir}/zt-approver-ui/node_modules"))
}

val buildApproverUi by tasks.registering(com.github.gradle.node.npm.task.NpmTask::class) {
    description = "Builds the Approval Center SPA (zt-approver-ui) via npm run build"
    dependsOn(npmInstallApproverUi)
    workingDir.set(file("${rootDir}/zt-approver-ui"))
    args.set(listOf("run", "build"))
    inputs.dir(file("${rootDir}/zt-approver-ui/src"))
    inputs.file(file("${rootDir}/zt-approver-ui/package.json"))
    outputs.dir(file("${rootDir}/zt-approver-ui/dist"))
}

// ── Chat Console (ADR-039) — a third independent npm project. Same pattern
// again: its own bundle because it has its own Keycloak client and its own realm
// role, and the people who use it have no business in the other two.
val npmInstallChatUi by tasks.registering(com.github.gradle.node.npm.task.NpmTask::class) {
    description = "Installs zt-chat-ui npm dependencies"
    workingDir.set(file("${rootDir}/zt-chat-ui"))
    args.set(listOf("install"))
    inputs.file(file("${rootDir}/zt-chat-ui/package.json"))
    outputs.dir(file("${rootDir}/zt-chat-ui/node_modules"))
}

val buildChatUi by tasks.registering(com.github.gradle.node.npm.task.NpmTask::class) {
    description = "Builds the Chat Console SPA (zt-chat-ui) via npm run build"
    dependsOn(npmInstallChatUi)
    workingDir.set(file("${rootDir}/zt-chat-ui"))
    args.set(listOf("run", "build"))
    inputs.dir(file("${rootDir}/zt-chat-ui/src"))
    inputs.file(file("${rootDir}/zt-chat-ui/package.json"))
    outputs.dir(file("${rootDir}/zt-chat-ui/dist"))
}

tasks.named<ProcessResources>("processResources") {
    dependsOn(buildAdminUi)
    dependsOn(buildApproverUi)
    dependsOn(buildChatUi)
    from(file("${rootDir}/zt-admin-ui/dist")) {
        into("static/admin")
    }
    from(file("${rootDir}/zt-approver-ui/dist")) {
        into("static/approver")
    }
    from(file("${rootDir}/zt-chat-ui/dist")) {
        into("static/chat")
    }
}

// ── Integration-test source set ───────────────────────────────────────────────
// src/it/java + src/it/resources run in the `integrationTest` task.
// Inherits all testImplementation/testRuntimeOnly deps from the unit-test scope.
sourceSets {
    create("it") {
        compileClasspath += sourceSets.main.get().output + sourceSets.test.get().output
        runtimeClasspath += sourceSets.main.get().output + sourceSets.test.get().output
        // The realm the integration tests import is GENERATED from the tracked
        // template (ADR-037), not the template itself: since the template carries
        // placeholders instead of client secrets, importing it would create clients
        // no test could authenticate as. The substituted values below exist only
        // inside a Testcontainer that lives for the length of the run.
        resources.srcDir(layout.buildDirectory.dir("it-realm"))
    }
}

// Fixture values, not credentials: they authenticate to a Keycloak container that
// is created and destroyed by the test run. Kept out of the tracked realm anyway,
// so that no file in this repository pairs a client id with a working secret.
val itRealmSecrets = mapOf(
    "__ZTE_SECRET_ZTE_GATEWAY__"                 to "it-fixture-zte-gateway",
    "__ZTE_SECRET_AGENT_A__"                     to "it-fixture-agent-a",
    "__ZTE_SECRET_AGENT_B__"                     to "it-fixture-agent-b",
    "__ZTE_SECRET_SERVICE_A__"                   to "it-fixture-service-a",
    "__ZTE_SECRET_CRM_ACCOUNT_HEALTH_EMEA_01__"  to "it-fixture-crm-account-health",
)

val generateItRealm by tasks.registering {
    description = "Substitutes fixture secrets into the realm template for integrationTest (ADR-037)"
    val template = rootDir.resolve("keycloak/realm-export.json")
    val outDir = layout.buildDirectory.dir("it-realm")
    inputs.file(template)
    outputs.dir(outDir)
    doLast {
        val target = outDir.get().file("realm-export.json").asFile
        target.parentFile.mkdirs()
        var text = template.readText()
        itRealmSecrets.forEach { (placeholder, value) -> text = text.replace(placeholder, value) }
        check(!text.contains("__ZTE_SECRET_")) { "realm template has a placeholder with no fixture value" }
        target.writeText(text)
    }
}

tasks.named("processItResources") { dependsOn(generateItRealm) }

val itImplementation: Configuration by configurations.getting {
    extendsFrom(configurations.testImplementation.get())
}
@Suppress("UNUSED_VARIABLE")
val itRuntimeOnly: Configuration by configurations.getting {
    extendsFrom(configurations.testRuntimeOnly.get())
}

dependencies {
    // ADR-037: secrets have no committed defaults any more, so a local run reads
    // them from the gitignored .env in the repo root — the same mechanism ADR-008
    // chose for zt-agents, and the same file docker compose substitutes from.
    implementation(libs.spring.dotenv)

    // Internal library
    implementation(project(":auth-library"))

    // Gateway & Security
    implementation(libs.spring.cloud.gateway)
    implementation(libs.spring.oauth2.resource)
    implementation(libs.spring.actuator)

    // Database — JDBC DataSource (Flyway migrations only)
    implementation(libs.spring.jdbc)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgres)
    runtimeOnly(libs.postgresql.driver)

    // Database — R2DBC (reactive runtime writes: request_logs audit trail, ADR-013.
    // ADR-012 removed this when its only prior use, PolicyService, was deleted —
    // restored here for a different purpose.)
    implementation(libs.spring.r2dbc)
    runtimeOnly(libs.r2dbc.postgresql)

    // mTLS outbound — Netty SslContext (version from Spring Boot BOM)
    implementation(libs.netty.handler)

    // YAML policy definition loader (Stage 10 / ADR-011)
    implementation(libs.jackson.dataformat.yaml)

    // OBO token creation — JJWT (api on compile path; impl+jackson at runtime via auth-library)
    implementation(libs.jjwt.api)

    // OpenAPI/Swagger docs for the gateway's own API (Stage 25 / ADR-025).
    // Same artifact+version already used by service-a/service-b since ADR-016's
    // amendment (there, to make their own /v3/api-docs discoverable by this
    // gateway's Inventory auto-discovery — here, for the gateway's own API).
    implementation(libs.springdoc.openapi.webflux.ui)

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
    itImplementation(libs.awaitility)
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
