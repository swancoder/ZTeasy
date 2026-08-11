package com.zte.gateway.it;

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

/**
 * E2E test for the APIM inventory registry (ADR-016) — the literal task
 * verification: onboarding triggers background discovery, and real routed
 * traffic updates {@code last_successful_call} without blocking the request.
 */
@DisplayName("APIM Inventory Registry — auto-discovery and telemetry (ADR-016)")
class InventoryRegistryIT extends BaseZteIntegrationTest {

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
