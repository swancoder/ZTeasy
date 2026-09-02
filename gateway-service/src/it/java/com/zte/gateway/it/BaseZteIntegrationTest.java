package com.zte.gateway.it;

import com.github.tomakehurst.wiremock.WireMockServer;
import dasniko.testcontainers.keycloak.KeycloakContainer;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.representations.idm.CredentialRepresentation;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Shared base for all ZTE integration tests.
 *
 * <p>Containers are started <em>once</em> (singleton pattern) via the static
 * initialiser block and shared across all subclasses. Spring's test context
 * cache sees the same {@code @DynamicPropertySource} output for all subclasses
 * that extend this class, so a single {@code ApplicationContext} is created and
 * reused for the entire integration-test suite — no restart overhead.
 *
 * <p>Infrastructure summary:
 * <ul>
 *   <li><b>PostgreSQL 16</b> — Flyway migrations run at context startup, seeding
 *       the {@code access_policies} table (ADMIN → /api/v1/service-a/**).</li>
 *   <li><b>Keycloak 24.0.4</b> — {@code zte-realm} imported from
 *       {@code realm-export.json}; {@code zte-admin} password set via Admin Client.</li>
 *   <li><b>WireMock</b> — replaces service-a, service-b, and the MCP backend
 *       ({@code mcp-backend.uri}); stubs reset before each test via
 *       {@link #resetStubs()}.</li>
 * </ul>
 */
@SpringBootTest(
    classes  = com.zte.gateway.GatewayApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@ActiveProfiles("it")
abstract class BaseZteIntegrationTest {

    // ── Shared password used for all test users ──────────────────────────────
    /**
     * Password this test sets on its own throwaway user inside the ephemeral
     * Testcontainers Keycloak — not a deployment credential, and deliberately
     * not a value any real environment uses, so a grep for real passwords in
     * this repository stays empty.
     */
    protected static final String TEST_PASSWORD  = "it-fixture-password";
    protected static final String ADMIN_USERNAME = "zte-admin";
    protected static final String USER_USERNAME  = "zte-test-user";

    // ── Singleton containers (started exactly once per JVM) ──────────────────

    static final PostgreSQLContainer<?> POSTGRES;
    /**
     * Fixture credentials for the containers this class creates (ADR-037). They are
     * not secrets: the Postgres and Keycloak instances they unlock are created at
     * the start of the run and destroyed at the end of it. The realm the tests
     * import is generated from the tracked template, which carries placeholders —
     * so no file in this repository pairs a client id with a working secret.
     */
    static final String IT_DB_PASSWORD             = "it-fixture-postgres";
    static final String IT_GATEWAY_CLIENT_SECRET   = "it-fixture-zte-gateway";
    static final String IT_CRM_AGENT_SECRET        = "it-fixture-crm-account-health";

    static final KeycloakContainer      KEYCLOAK;
    static final WireMockServer         WIREMOCK;

    static {
        // Docker Desktop on WSL2 only accepts Docker API ≥ v1.44 on /var/run/docker.sock.
        // Testcontainers uses a shaded copy of docker-java whose DefaultDockerClientConfig
        // calls overrideDockerPropertiesWithSystemProperties() — the config key is "api.version"
        // (not "docker.api.version" or "DOCKER_API_VERSION" which is the Docker CLI env var).
        // Set it before any container constructor is called so it is picked up during the
        // DockerClientFactory.getOrInitializeStrategy() call that fires inside KeycloakContainer().
        System.setProperty("api.version", "1.45");

        POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
                .withDatabaseName("zte_db")
                .withUsername("zte_user")
                .withPassword(IT_DB_PASSWORD);

        KEYCLOAK = new KeycloakContainer("quay.io/keycloak/keycloak:24.0.4")
                .withRealmImportFile("realm-export.json"); // on classpath from keycloak/ dir

        WIREMOCK = new WireMockServer(wireMockConfig().dynamicPort());

        POSTGRES.start();
        KEYCLOAK.start();
        WIREMOCK.start();

        provisionTestUsers();
    }

    // ── DynamicPropertySource wires containers into the Spring context ────────

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        // JDBC (Flyway)
        registry.add("spring.datasource.url",      POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);

        // Flyway
        registry.add("spring.flyway.url",      POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user",     POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);

        // R2DBC
        registry.add("spring.r2dbc.url",      () -> toR2dbcUrl(POSTGRES.getJdbcUrl()));
        registry.add("spring.r2dbc.username", POSTGRES::getUsername);
        registry.add("spring.r2dbc.password", POSTGRES::getPassword);

        // Keycloak JWT validation — both issuer-uri (for iss claim) and jwk-set-uri.
        // getAuthServerUrl() returns the URL WITHOUT a trailing slash, so we add one.
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri",
                () -> KEYCLOAK.getAuthServerUrl() + "/realms/zte-realm");
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                () -> KEYCLOAK.getAuthServerUrl() + "/realms/zte-realm/protocol/openid-connect/certs");

        // MCP backend (McpBackendClient) → WireMock as well; see McpProxyIT
        registry.add("mcp-backend.uri", () -> "http://localhost:" + WIREMOCK.port());

        // Blank out application.yml's default service-a.uri/service-b.uri (ADR-017) —
        // otherwise InventoryBootstrapSeeder would seed both at their real
        // https://localhost:8081/8082 defaults during context startup, before
        // seedRoutingRegistryOnce() (below) gets a chance to seed the WireMock-pointed
        // entries these tests actually need, 409-conflicting on the name. Tests seed
        // their own routing registry entries explicitly instead.
        registry.add("service-a.uri", () -> "");
        registry.add("service-b.uri", () -> "");

        // IdP identity sync (ADR-014) — KeycloakIdpAdapter calls the real Testcontainers
        // Keycloak's Admin REST API using zte-gateway's service account (realm-export.json
        // grants it view-users/view-realm). Long sync interval — tests trigger sync manually
        // via POST /api/v1/admin/identities/sync, not the @Scheduled job.
        registry.add("zte.idp.keycloak.base-uri", KEYCLOAK::getAuthServerUrl);
        registry.add("zte.idp.sync-interval-ms", () -> "3600000");

        // Dynamic routing (ADR-017) — InventoryRouteRefreshScheduler's periodic
        // RefreshRoutesEvent is the only thing that picks up a status change
        // AutoDiscoveryWorker/HealthPollingService make directly via InventoryRepository
        // (InventoryService.create/update/delete publish their own immediate refresh, but
        // that fires while a freshly seeded entry is still PENDING — excluded from routing
        // — not yet ACTIVE/WARNING). The production default (30s) would make every IT test
        // that routes through a freshly seeded service wait up to 30s; short-circuited here.
        registry.add("zte.routing.refresh-interval-ms", () -> "500");
    }

    // ── Dynamic port injection ────────────────────────────────────────────────

    @Value("${local.server.port}")
    protected int gatewayPort;

    // ── Before each test: reset WireMock stubs ───────────────────────────────

    @BeforeEach
    void resetStubs() {
        WIREMOCK.resetAll();
    }

    // ── Seed the dynamic-routing registry (ADR-017) ───────────────────────────

    /**
     * The old hardcoded {@code service-a}/{@code service-b} Gateway routes were
     * replaced with {@code InventoryRouteDefinitionLocator} (ADR-017), which
     * sources routes from {@code inventory_services} — so
     * {@code /api/v1/service-a/**}/{@code /api/v1/service-b/**} only route
     * anywhere if those two names are actually registered. Every IT class that
     * routes through the gateway to either name (nearly all of them) needs this;
     * seeding it here once, rather than per-class, matches this base class's own
     * "one shared context for the whole suite" design.
     *
     * <p>Guarded by a static flag, not a tolerate-409-and-move-on POST, since
     * {@link #configure} wires the same {@code WIREMOCK} instance/port for
     * every subclass in this single, shared {@code ApplicationContext} — there
     * is only ever one seeding to do, not one per test class. Not thread-safe
     * against parallel test execution, but this suite doesn't run one (JUnit5's
     * default sequential execution, unmodified here) — no lock needed for a
     * flag only ever read/written from one thread at a time.
     */
    private static boolean routingRegistrySeeded = false;

    @BeforeEach
    void seedRoutingRegistryOnce() {
        if (routingRegistrySeeded) {
            return;
        }
        String adminToken = getAdminToken();
        String wireMockUri = "http://localhost:" + WIREMOCK.port();
        seedInventoryEntry(adminToken, "service-a", wireMockUri);
        seedInventoryEntry(adminToken, "service-b", wireMockUri);
        routingRegistrySeeded = true;
    }

    private void seedInventoryEntry(String adminToken, String name, String baseUri) {
        RestAssured.given()
                .baseUri("http://localhost:" + gatewayPort)
                .header("Authorization", "Bearer " + adminToken)
                .contentType(ContentType.JSON)
                .body(Map.of("name", name, "targetType", "REST", "baseUrl", baseUri))
            .when()
                .post("/api/v1/admin/inventory")
            .then()
                .statusCode(201);

        // Freshly created, this row is PENDING — excluded from routing — until
        // AutoDiscoveryWorker's async probe settles it to ACTIVE/WARNING (both
        // routable). That probe writes status via InventoryRepository directly, not
        // through InventoryService, so it never publishes its own RefreshRoutesEvent
        // — only the periodic scheduler (configure()'s shortened 500ms interval in
        // this profile) picks the change up. Wait for status to settle, then for one
        // more scheduler cycle, so routing is actually live before any test runs.
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            String status = RestAssured.given()
                    .baseUri("http://localhost:" + gatewayPort)
                    .header("Authorization", "Bearer " + adminToken)
                .when()
                    .get("/api/v1/admin/inventory")
                .then()
                    .statusCode(200)
                    .extract().response()
                    .jsonPath().getString("find { it.name == '" + name + "' }.status");
            assertThat(status).isIn("ACTIVE", "WARNING");
        });
        // >= one more 500ms scheduler cycle, so the route reflects the now-settled status.
        await().pollDelay(Duration.ofMillis(750)).atMost(Duration.ofMillis(1500)).until(() -> true);
    }

    // ── Token helpers ─────────────────────────────────────────────────────────

    /**
     * Returns a JWT access token for the ADMIN role user ({@code zte-admin}).
     * Uses the Resource Owner Password Credentials grant.
     */
    protected String getAdminToken() {
        return fetchToken(ADMIN_USERNAME);
    }

    /**
     * Returns a JWT access token for the USER role test user ({@code zte-test-user}).
     */
    protected String getUserToken() {
        return fetchToken(USER_USERNAME);
    }

    /**
     * Returns a JWT access token for an MCP agent client via the Client
     * Credentials grant — see ADR-010. Unlike
     * {@link #getAdminToken()}/{@link #getUserToken()}, there is no username —
     * the token identifies the client itself (via the {@code azp} claim).
     */
    protected String getAgentToken(String clientId, String clientSecret) {
        return RestAssured.given()
                .contentType(ContentType.URLENC)
                .formParam("grant_type",    "client_credentials")
                .formParam("client_id",     clientId)
                .formParam("client_secret", clientSecret)
                .post(KEYCLOAK.getAuthServerUrl() + "/realms/zte-realm/protocol/openid-connect/token")
                .then().statusCode(200)
                .extract().path("access_token");
    }

    private String fetchToken(String username) {
        return RestAssured.given()
                .contentType(ContentType.URLENC)
                .formParam("grant_type",    "password")
                .formParam("client_id",     "zte-gateway")
                .formParam("client_secret", IT_GATEWAY_CLIENT_SECRET)
                .formParam("username",      username)
                .formParam("password",      TEST_PASSWORD)
                .post(KEYCLOAK.getAuthServerUrl() + "/realms/zte-realm/protocol/openid-connect/token")
                .then().statusCode(200)
                .extract().path("access_token");
    }

    // ── Keycloak provisioning (runs once, in static block) ───────────────────

    /**
     * Sets passwords for both test users.
     *
     * <p>Both users are declared in realm-export.json with empty {@code credentials},
     * so Keycloak imports them without a password. This method sets a known password
     * via the Admin API so ROPC token requests can succeed during tests.
     */
    private static void provisionTestUsers() {
        try (Keycloak admin = KEYCLOAK.getKeycloakAdminClient()) {
            var realm = admin.realm("zte-realm");

            for (String username : List.of(ADMIN_USERNAME, USER_USERNAME)) {
                String userId = realm.users().search(username).get(0).getId();
                realm.users().get(userId).resetPassword(credential(TEST_PASSWORD));
            }
        }
    }

    private static CredentialRepresentation credential(String password) {
        CredentialRepresentation cred = new CredentialRepresentation();
        cred.setType(CredentialRepresentation.PASSWORD);
        cred.setValue(password);
        cred.setTemporary(false);
        return cred;
    }

    private static String toR2dbcUrl(String jdbcUrl) {
        // jdbc:postgresql://host:port/db[?params] → r2dbc:postgresql://host:port/db
        return jdbcUrl.replace("jdbc:", "r2dbc:").replaceAll("\\?.*$", "");
    }
}
