package com.zte.gateway.it;

import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * ADR-013: proves the literal task verification — both allowed and denied
 * requests produce a {@code request_logs} row with the correct {@code trace_id}
 * and a non-null {@code client_ip}, read back via {@code GET /api/v1/admin/audit-logs}.
 *
 * <p>The write is async (off the request thread — see {@code RequestLogAuditService}),
 * so assertions poll with Awaitility rather than checking immediately after the
 * HTTP response returns.
 */
@DisplayName("Request Audit Trail (ADR-013)")
class RequestAuditIT extends BaseZteIntegrationTest {

    private static final String STUB_RESPONSE = """
            {"service":"service-a","message":"Hello from Protected Service A"}
            """;

    @Test
    @DisplayName("Allowed request (ADMIN, 200) produces a request_logs row with matching trace_id and client_ip")
    void allowedRequest_generatesAuditRow() {
        WIREMOCK.stubFor(get(urlPathEqualTo("/api/v1/service-a/hello"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(STUB_RESPONSE)));

        String traceId = "it-allowed-" + UUID.randomUUID();
        String adminToken = getAdminToken();

        given()
            .baseUri("http://localhost:" + gatewayPort)
            .header("Authorization", "Bearer " + adminToken)
            .header("X-Request-Id", traceId)
        .when()
            .get("/api/v1/service-a/hello")
        .then()
            .statusCode(200);

        assertAuditRowEventuallyExists(adminToken, traceId, 200);
    }

    @Test
    @DisplayName("Denied request (USER role, no policy, 403) also produces a request_logs row")
    void deniedRequest_generatesAuditRow() {
        String traceId = "it-denied-" + UUID.randomUUID();
        String userToken = getUserToken();
        String adminToken = getAdminToken();

        given()
            .baseUri("http://localhost:" + gatewayPort)
            .header("Authorization", "Bearer " + userToken)
            .header("X-Request-Id", traceId)
        .when()
            .get("/api/v1/service-a/hello")
        .then()
            .statusCode(403);

        assertAuditRowEventuallyExists(adminToken, traceId, 403);
    }

    private void assertAuditRowEventuallyExists(String adminToken, String traceId, int expectedStatus) {
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            Response res = given()
                    .baseUri("http://localhost:" + gatewayPort)
                    .header("Authorization", "Bearer " + adminToken)
                .when()
                    .get("/api/v1/admin/audit-logs")
                .then()
                    .statusCode(200)
                    .extract().response();

            List<Map<String, Object>> allLogs = res.jsonPath().getList("");
            List<Map<String, Object>> matching = allLogs.stream()
                    .filter(row -> traceId.equals(row.get("traceId")))
                    .toList();

            assertThat(matching).hasSize(1);
            Map<String, Object> row = matching.get(0);
            assertThat(row.get("clientIp")).isNotNull();
            assertThat(((Number) row.get("statusCode")).intValue()).isEqualTo(expectedStatus);
        });
    }
}
