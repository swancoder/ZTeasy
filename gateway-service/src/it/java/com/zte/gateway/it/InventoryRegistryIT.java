package com.zte.gateway.it;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

/**
 * E2E test for the APIM inventory registry (ADR-016) — the literal task
 * verification: onboarding triggers background discovery, and real routed
 * traffic updates {@code last_successful_call} without blocking the request.
 */
@DisplayName("APIM Inventory Registry — auto-discovery and telemetry (ADR-016)")
class InventoryRegistryIT extends BaseZteIntegrationTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    @DisplayName("Onboarding a REST service with a reachable schema endpoint eventually goes ACTIVE")
    void onboardRestService_discoverySucceeds_becomesActive() {
        String name = "rest-active-" + UUID.randomUUID();
        WIREMOCK.stubFor(get(urlPathEqualTo("/v3/api-docs"))
                .willReturn(aResponse().withStatus(200).withBody("{\"openapi\":\"3.0.0\"}")));

        String adminToken = getAdminToken();
        String id = onboard(adminToken, name, "REST", "http://localhost:" + WIREMOCK.port());

        assertStatusEventually(adminToken, id, "ACTIVE");
    }

    @Test
    @DisplayName("Onboarding a REST service whose schema endpoint 404s eventually goes WARNING")
    void onboardRestService_discoveryFails_becomesWarning() {
        String name = "rest-warning-" + UUID.randomUUID();
        // Deliberately no /v3/api-docs stub registered for this test — WireMock's
        // default response to any unmatched request is 404, which is exactly the
        // "unreachable/degraded" outcome this test wants to prove maps to WARNING.

        String adminToken = getAdminToken();
        String id = onboard(adminToken, name, "REST", "http://localhost:" + WIREMOCK.port());

        assertStatusEventually(adminToken, id, "WARNING");
    }

    @Test
    @DisplayName("Onboarding an MCP agent with a reachable tools/list endpoint eventually goes ACTIVE")
    void onboardMcpAgent_discoverySucceeds_becomesActive() {
        String name = "mcp-active-" + UUID.randomUUID();
        WIREMOCK.stubFor(post(urlPathEqualTo("/message"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":[]}}")));

        String adminToken = getAdminToken();
        String id = onboard(adminToken, name, "MCP", "http://localhost:" + WIREMOCK.port());

        assertStatusEventually(adminToken, id, "ACTIVE");
    }

    @Test
    @DisplayName("A successful REST discovery captures the OpenAPI body, fetchable on demand")
    void onboardRestService_discoverySucceeds_capturesSchema() {
        String name = "rest-schema-" + UUID.randomUUID();
        String openApiBody = "{\"openapi\":\"3.0.0\",\"info\":{\"title\":\"stub\"}}";
        WIREMOCK.stubFor(get(urlPathEqualTo("/v3/api-docs"))
                .willReturn(aResponse().withStatus(200).withBody(openApiBody)));

        String adminToken = getAdminToken();
        String id = onboard(adminToken, name, "REST", "http://localhost:" + WIREMOCK.port());
        assertStatusEventually(adminToken, id, "ACTIVE");

        assertSchemaEventually(adminToken, id, openApiBody);
    }

    @Test
    @DisplayName("A successful MCP discovery captures the tools/list response, fetchable on demand")
    void onboardMcpAgent_discoverySucceeds_capturesSchema() {
        String name = "mcp-schema-" + UUID.randomUUID();
        String toolsListBody = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":[{\"name\":\"echo\"}]}}";
        WIREMOCK.stubFor(post(urlPathEqualTo("/message"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(toolsListBody)));

        String adminToken = getAdminToken();
        String id = onboard(adminToken, name, "MCP", "http://localhost:" + WIREMOCK.port());
        assertStatusEventually(adminToken, id, "ACTIVE");

        assertSchemaEventually(adminToken, id, toolsListBody);
    }

    @Test
    @DisplayName("A custom docs_url is probed instead of the {base_url}/v3/api-docs convention")
    void onboardRestService_withCustomDocsUrl_probesDocsUrlInstead() {
        String name = "rest-custom-docs-" + UUID.randomUUID();
        String customBody = "{\"openapi\":\"3.0.0\",\"info\":{\"title\":\"custom-path\"}}";
        // /v3/api-docs deliberately left unstubbed (404) — if discovery still used the
        // default convention despite docs_url being set, this would land on WARNING.
        WIREMOCK.stubFor(get(urlPathEqualTo("/openapi-custom.json"))
                .willReturn(aResponse().withStatus(200).withBody(customBody)));

        String adminToken = getAdminToken();
        String id = onboard(adminToken, name, "REST", "http://localhost:" + WIREMOCK.port(),
                "http://localhost:" + WIREMOCK.port() + "/openapi-custom.json");

        assertStatusEventually(adminToken, id, "ACTIVE");
        assertSchemaEventually(adminToken, id, customBody);
    }

    @Test
    @DisplayName("Synchronous fetch: success returns 200 immediately and the schema is captured")
    void fetchSchemaNow_success_returns200AndCapturesImmediately() {
        String name = "sync-fetch-ok-" + UUID.randomUUID();
        String body = "{\"openapi\":\"3.0.0\"}";
        WIREMOCK.stubFor(get(urlPathEqualTo("/v3/api-docs")).willReturn(aResponse().withStatus(200).withBody(body)));

        String adminToken = getAdminToken();
        String id = onboard(adminToken, name, "REST", "http://localhost:" + WIREMOCK.port());
        assertStatusEventually(adminToken, id, "ACTIVE"); // let background discovery settle first

        fetchSchemaSync(adminToken, id).then().statusCode(200);
        assertSchemaEventually(adminToken, id, body);
        assertHasSchemaEventually(adminToken, id, true);
    }

    @Test
    @DisplayName("Synchronous fetch: an unreachable target returns 502 with a clear message")
    void fetchSchemaNow_unreachableTarget_returns502() {
        String name = "sync-fetch-unreachable-" + UUID.randomUUID();
        // Port 1 is a reserved, always-refused port — no stub needed to make this fail.
        String adminToken = getAdminToken();
        String id = onboard(adminToken, name, "REST", "http://localhost:1");

        fetchSchemaSync(adminToken, id)
                .then()
                .statusCode(502)
                .body("error", notNullValue());
    }

    @Test
    @DisplayName("Synchronous fetch: a 2xx with a non-JSON body returns 502, unlike the lenient background worker")
    void fetchSchemaNow_invalidJsonBody_returns502_evenThoughBackgroundWorkerMarksActive() {
        String name = "sync-fetch-invalid-json-" + UUID.randomUUID();
        WIREMOCK.stubFor(get(urlPathEqualTo("/v3/api-docs"))
                .willReturn(aResponse().withStatus(200).withBody("<html>not json</html>")));

        String adminToken = getAdminToken();
        String id = onboard(adminToken, name, "REST", "http://localhost:" + WIREMOCK.port());
        // The passive worker is lenient — 2xx is 2xx, so this still reaches ACTIVE even
        // though nothing valid was captured (the exact status-doesn't-imply-schema gap
        // hasSchema exists to close).
        assertStatusEventually(adminToken, id, "ACTIVE");
        assertHasSchemaEventually(adminToken, id, false);

        // The synchronous, UI-triggered fetch against the same unusable body is stricter.
        fetchSchemaSync(adminToken, id)
                .then()
                .statusCode(502)
                .body("error", notNullValue());
    }

    @Test
    @DisplayName("Synchronous fetch: an unknown id returns 404")
    void fetchSchemaNow_unknownId_returns404() {
        String adminToken = getAdminToken();
        fetchSchemaSync(adminToken, UUID.randomUUID().toString()).then().statusCode(404);
    }

    @Test
    @DisplayName("Synchronous fetch acts as a 'Retry Discovery' — a WARNING service recovers to ACTIVE " +
            "once its target becomes reachable, with no separate retry mechanism needed")
    void fetchSchemaNow_onWarningService_recoversToActiveOnceReachable() {
        String name = "warning-recovery-" + UUID.randomUUID();
        // No /v3/api-docs stub yet — discovery fails, lands on WARNING.
        String adminToken = getAdminToken();
        String id = onboard(adminToken, name, "REST", "http://localhost:" + WIREMOCK.port());
        assertStatusEventually(adminToken, id, "WARNING");

        // The target becomes reachable later (e.g. the operator fixed a config issue) —
        // clicking "Fetch" (not a dedicated "Retry" action) is the UI's only recovery path.
        String body = "{\"openapi\":\"3.0.0\"}";
        WIREMOCK.stubFor(get(urlPathEqualTo("/v3/api-docs")).willReturn(aResponse().withStatus(200).withBody(body)));

        fetchSchemaSync(adminToken, id).then().statusCode(200);
        assertStatusEventually(adminToken, id, "ACTIVE");
        assertHasSchemaEventually(adminToken, id, true);
    }

    @Test
    @DisplayName("Schema endpoint 404s for a service with nothing captured yet, and for an unknown id")
    void schema_notCaptured_returns404() {
        String name = "rest-no-schema-" + UUID.randomUUID();
        // Deliberately no /v3/api-docs stub — discovery fails (WARNING), nothing to capture.

        String adminToken = getAdminToken();
        String id = onboard(adminToken, name, "REST", "http://localhost:" + WIREMOCK.port());
        assertStatusEventually(adminToken, id, "WARNING");

        given()
                .baseUri("http://localhost:" + gatewayPort)
                .header("Authorization", "Bearer " + adminToken)
            .when()
                .get("/api/v1/admin/inventory/" + id + "/schema")
            .then()
                .statusCode(404);

        given()
                .baseUri("http://localhost:" + gatewayPort)
                .header("Authorization", "Bearer " + adminToken)
            .when()
                .get("/api/v1/admin/inventory/" + UUID.randomUUID() + "/schema")
            .then()
                .statusCode(404);
    }

    @Test
    @DisplayName("CRUD: list, update, and delete a registered service")
    void crud_updateAndDelete() {
        String name = "crud-" + UUID.randomUUID();
        WIREMOCK.stubFor(get(urlPathEqualTo("/v3/api-docs"))
                .willReturn(aResponse().withStatus(200)));

        String adminToken = getAdminToken();
        String id = onboard(adminToken, name, "REST", "http://localhost:" + WIREMOCK.port());

        given()
            .baseUri("http://localhost:" + gatewayPort)
            .header("Authorization", "Bearer " + adminToken)
        .when()
            .get("/api/v1/admin/inventory")
        .then()
            .statusCode(200)
            .body("find { it.id == '" + id + "' }.name", equalTo(name));

        given()
            .baseUri("http://localhost:" + gatewayPort)
            .header("Authorization", "Bearer " + adminToken)
            .contentType("application/json")
            .body(Map.of("name", name, "targetType", "REST", "baseUrl", "http://localhost:" + WIREMOCK.port() + "/updated"))
        .when()
            .put("/api/v1/admin/inventory/" + id)
        .then()
            .statusCode(200)
            .body("baseUrl", equalTo("http://localhost:" + WIREMOCK.port() + "/updated"));

        given()
            .baseUri("http://localhost:" + gatewayPort)
            .header("Authorization", "Bearer " + adminToken)
        .when()
            .delete("/api/v1/admin/inventory/" + id)
        .then()
            .statusCode(204);

        given()
            .baseUri("http://localhost:" + gatewayPort)
            .header("Authorization", "Bearer " + adminToken)
        .when()
            .get("/api/v1/admin/inventory")
        .then()
            .statusCode(200)
            .body("find { it.id == '" + id + "' }", equalTo(null));
    }

    @Test
    @DisplayName("Onboarding the same name twice is rejected with 409")
    void onboard_duplicateName_returns409() {
        String name = "dup-" + UUID.randomUUID();
        WIREMOCK.stubFor(get(urlPathEqualTo("/v3/api-docs")).willReturn(aResponse().withStatus(200)));
        String adminToken = getAdminToken();

        onboard(adminToken, name, "REST", "http://localhost:" + WIREMOCK.port());

        given()
            .baseUri("http://localhost:" + gatewayPort)
            .header("Authorization", "Bearer " + adminToken)
            .contentType("application/json")
            .body(Map.of("name", name, "targetType", "REST", "baseUrl", "http://localhost:" + WIREMOCK.port()))
        .when()
            .post("/api/v1/admin/inventory")
        .then()
            .statusCode(409);
    }

    @Test
    @DisplayName("Renaming a service via PUT to collide with another existing name is rejected with 409")
    void update_renameToExistingName_returns409() {
        WIREMOCK.stubFor(get(urlPathEqualTo("/v3/api-docs")).willReturn(aResponse().withStatus(200)));
        String adminToken = getAdminToken();

        String existingName = "rename-target-" + UUID.randomUUID();
        onboard(adminToken, existingName, "REST", "http://localhost:" + WIREMOCK.port());
        String idToRename = onboard(adminToken, "rename-source-" + UUID.randomUUID(), "REST",
                "http://localhost:" + WIREMOCK.port());

        given()
            .baseUri("http://localhost:" + gatewayPort)
            .header("Authorization", "Bearer " + adminToken)
            .contentType("application/json")
            .body(Map.of("name", existingName, "targetType", "REST", "baseUrl", "http://localhost:" + WIREMOCK.port()))
        .when()
            .put("/api/v1/admin/inventory/" + idToRename)
        .then()
            .statusCode(409);
    }

    @Test
    @DisplayName("Updating without renaming (name unchanged) succeeds — no false-positive 409 against itself")
    void update_sameNameUnchanged_succeeds() {
        WIREMOCK.stubFor(get(urlPathEqualTo("/v3/api-docs")).willReturn(aResponse().withStatus(200)));
        String adminToken = getAdminToken();
        String name = "update-self-" + UUID.randomUUID();
        String id = onboard(adminToken, name, "REST", "http://localhost:" + WIREMOCK.port());

        given()
            .baseUri("http://localhost:" + gatewayPort)
            .header("Authorization", "Bearer " + adminToken)
            .contentType("application/json")
            .body(Map.of("name", name, "targetType", "REST", "baseUrl", "http://localhost:" + WIREMOCK.port() + "/v2"))
        .when()
            .put("/api/v1/admin/inventory/" + id)
        .then()
            .statusCode(200)
            .body("baseUrl", equalTo("http://localhost:" + WIREMOCK.port() + "/v2"));
    }

    @Test
    @DisplayName("Updating an unknown id returns 404")
    void update_unknownId_returns404() {
        String adminToken = getAdminToken();

        given()
            .baseUri("http://localhost:" + gatewayPort)
            .header("Authorization", "Bearer " + adminToken)
            .contentType("application/json")
            .body(Map.of("name", "ghost-" + UUID.randomUUID(), "targetType", "REST",
                    "baseUrl", "http://localhost:" + WIREMOCK.port()))
        .when()
            .put("/api/v1/admin/inventory/" + UUID.randomUUID())
        .then()
            .statusCode(404);
    }

    @Test
    @DisplayName("Real routed traffic updates last_successful_call asynchronously, without blocking the response")
    void routedTraffic_updatesLastSuccessfulCall() {
        WIREMOCK.stubFor(get(urlPathEqualTo("/api/v1/service-a/hello"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"service\":\"service-a\"}")));
        WIREMOCK.stubFor(get(urlPathEqualTo("/v3/api-docs")).willReturn(aResponse().withStatus(200)));

        String adminToken = getAdminToken();
        // Name must match RequestTargetResolver.targetService("/api/v1/service-a/hello") -> "service-a"
        // for the passive telemetry hook (a plain string match) to find this row.
        String id = onboard(adminToken, "service-a", "REST", "http://localhost:" + WIREMOCK.port());
        assertStatusEventually(adminToken, id, "ACTIVE");

        given()
            .baseUri("http://localhost:" + gatewayPort)
            .header("Authorization", "Bearer " + adminToken)
        .when()
            .get("/api/v1/service-a/hello")
        .then()
            .statusCode(200);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            Response res = given()
                    .baseUri("http://localhost:" + gatewayPort)
                    .header("Authorization", "Bearer " + adminToken)
                .when()
                    .get("/api/v1/admin/inventory")
                .then()
                    .statusCode(200)
                    .extract().response();

            List<Map<String, Object>> entries = res.jsonPath().getList("");
            Map<String, Object> row = entries.stream()
                    .filter(e -> id.equals(e.get("id")))
                    .findFirst().orElseThrow();
            assertThat(row.get("lastSuccessfulCall")).isNotNull();
        });
    }

    private String onboard(String adminToken, String name, String targetType, String baseUrl) {
        return given()
                .baseUri("http://localhost:" + gatewayPort)
                .header("Authorization", "Bearer " + adminToken)
                .contentType("application/json")
                .body(Map.of("name", name, "targetType", targetType, "baseUrl", baseUrl))
            .when()
                .post("/api/v1/admin/inventory")
            .then()
                .statusCode(201)
                .extract().path("id");
    }

    private String onboard(String adminToken, String name, String targetType, String baseUrl, String docsUrl) {
        return given()
                .baseUri("http://localhost:" + gatewayPort)
                .header("Authorization", "Bearer " + adminToken)
                .contentType("application/json")
                .body(Map.of("name", name, "targetType", targetType, "baseUrl", baseUrl, "docsUrl", docsUrl))
            .when()
                .post("/api/v1/admin/inventory")
            .then()
                .statusCode(201)
                .extract().path("id");
    }

    private Response fetchSchemaSync(String adminToken, String id) {
        return given()
                .baseUri("http://localhost:" + gatewayPort)
                .header("Authorization", "Bearer " + adminToken)
            .when()
                .post("/api/v1/admin/inventory/" + id + "/schema/fetch");
    }

    private void assertHasSchemaEventually(String adminToken, String id, boolean expected) {
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            Response res = given()
                    .baseUri("http://localhost:" + gatewayPort)
                    .header("Authorization", "Bearer " + adminToken)
                .when()
                    .get("/api/v1/admin/inventory")
                .then()
                    .statusCode(200)
                    .extract().response();

            List<Map<String, Object>> entries = res.jsonPath().getList("");
            Map<String, Object> row = entries.stream()
                    .filter(e -> id.equals(e.get("id")))
                    .findFirst().orElseThrow();
            assertThat(row.get("hasSchema")).isEqualTo(expected);
        });
    }

    /**
     * Compares the captured schema structurally, not byte-for-byte —
     * Postgres's {@code jsonb} column canonicalizes on write (re-serializes,
     * reorders keys), so the round-tripped body is valid-but-reformatted
     * JSON, not the original text (found live running this test).
     */
    private void assertSchemaEventually(String adminToken, String id, String expectedJson) {
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            Response res = given()
                    .baseUri("http://localhost:" + gatewayPort)
                    .header("Authorization", "Bearer " + adminToken)
                .when()
                    .get("/api/v1/admin/inventory/" + id + "/schema")
                .then()
                    .statusCode(200)
                    .extract().response();

            assertThat(OBJECT_MAPPER.readTree(res.asString())).isEqualTo(OBJECT_MAPPER.readTree(expectedJson));
        });
    }

    private void assertStatusEventually(String adminToken, String id, String expectedStatus) {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Response res = given()
                    .baseUri("http://localhost:" + gatewayPort)
                    .header("Authorization", "Bearer " + adminToken)
                .when()
                    .get("/api/v1/admin/inventory")
                .then()
                    .statusCode(200)
                    .extract().response();

            List<Map<String, Object>> entries = res.jsonPath().getList("");
            Map<String, Object> row = entries.stream()
                    .filter(e -> id.equals(e.get("id")))
                    .findFirst().orElseThrow();
            assertThat(row.get("status")).isEqualTo(expectedStatus);
        });
    }
}
