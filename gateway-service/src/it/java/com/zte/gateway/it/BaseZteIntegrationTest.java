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

import java.util.List;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

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
    protected static final String TEST_PASSWORD  = "Admin@123!";
    protected static final String ADMIN_USERNAME = "zte-admin";
    protected static final String USER_USERNAME  = "zte-test-user";

    // ── Singleton containers (started exactly once per JVM) ──────────────────

    static final PostgreSQLContainer<?> POSTGRES;
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
                .withPassword("zte_pass");

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

        // Downstream services → WireMock (HTTP, no mTLS needed in test)
        registry.add("service-a.uri", () -> "http://localhost:" + WIREMOCK.port());
        registry.add("service-b.uri", () -> "http://localhost:" + WIREMOCK.port());

        // MCP backend (McpBackendClient) → WireMock as well; see McpProxyIT
        registry.add("mcp-backend.uri", () -> "http://localhost:" + WIREMOCK.port());
    }

    // ── Dynamic port injection ────────────────────────────────────────────────

    @Value("${local.server.port}")
    protected int gatewayPort;

    // ── Before each test: reset WireMock stubs ───────────────────────────────

    @BeforeEach
    void resetStubs() {
        WIREMOCK.resetAll();
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
     * Returns a JWT access token for an MCP agent client ({@code agent-a} /
     * {@code agent-b}) via the Client Credentials grant — see ADR-010. Unlike
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
                .formParam("client_secret", "zte-gateway-secret")
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
