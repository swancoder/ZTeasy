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
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Service-to-service policy enforcement (ADR-017) — the literal task
 * verification: a caller authenticated as {@code service-a} (a Keycloak
 * machine client added for this — see {@code keycloak/realm-export.json}
 * and ADR-017 for why this is a <em>different</em> path than the real
 * service-a app's own internal, direct-mTLS call to service-b) can reach
 * service-b's normal endpoint (a {@code service2service} {@code ALLOW}
 * rule in {@code zte-policies.yaml}), but not {@code /restricted} (no
 * matching rule — falls through to {@code zte.policy.default-effect},
 * deny-by-default).
 */
@DisplayName("Service-to-service policy enforcement (ADR-017)")
class ServiceToServiceIT extends BaseZteIntegrationTest {

    private static final String SERVICE_A_CLIENT_SECRET = "it-fixture-service-a";   // fixture, see BaseZteIntegrationTest

    @Test
    @DisplayName("service-a (client credentials) calling service-b's allowed /context endpoint succeeds and logs the OBO user")
    void serviceA_callsServiceBContext_succeedsAndLogsOboUser() {
        WIREMOCK.stubFor(get(urlPathEqualTo("/api/v1/service-b/context"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"service\":\"service-b\",\"sub\":\"service-account-service-a\"}")));

        String traceId = "it-s2s-allow-" + UUID.randomUUID();
        String serviceAToken = getAgentToken("service-a", SERVICE_A_CLIENT_SECRET);
        String adminToken = getAdminToken();

        given()
            .baseUri("http://localhost:" + gatewayPort)
            .header("Authorization", "Bearer " + serviceAToken)
            .header("X-Request-Id", traceId)
        .when()
            .get("/api/v1/service-b/context")
        .then()
            .statusCode(200);

        // The gateway minted and forwarded a signed OBO token for this call, same as
        // for any allowed request — UserContextPropagationFilter runs regardless of
        // caller type (interactive user vs. service2service), after this filter allows it.
        WIREMOCK.verify(getRequestedFor(urlPathEqualTo("/api/v1/service-b/context"))
                .withHeader("X-ZTE-User-Context", matching(".+")));

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            Map<String, Object> row = findAuditRow(adminToken, traceId);
            assertThat(row.get("initiatorClient")).isEqualTo("service-a");
            assertThat(row.get("targetService")).isEqualTo("service-b");
            assertThat(row.get("decisionEffect")).isEqualTo("ALLOW");
            assertThat(((Number) row.get("statusCode")).intValue()).isEqualTo(200);
            // originalUserObo is the client's own service-account subject here — there's
            // no human behind a pure service2service call — still non-null: the gateway's
            // audit trail always records whatever subject actually reached it.
            assertThat(row.get("originalUserObo")).isNotNull();
        });
    }

    @Test
    @DisplayName("service-a (client credentials) calling service-b's /restricted endpoint is denied with 403, never reaching service-b")
    void serviceA_callsServiceBRestricted_returns403WithoutReachingDownstream() {
        WIREMOCK.stubFor(get(urlPathEqualTo("/api/v1/service-b/restricted"))
                .willReturn(aResponse().withStatus(200))); // would succeed if the filter didn't deny first

        String traceId = "it-s2s-deny-" + UUID.randomUUID();
        String serviceAToken = getAgentToken("service-a", SERVICE_A_CLIENT_SECRET);
        String adminToken = getAdminToken();

        given()
            .baseUri("http://localhost:" + gatewayPort)
            .header("Authorization", "Bearer " + serviceAToken)
            .header("X-Request-Id", traceId)
        .when()
            .get("/api/v1/service-b/restricted")
        .then()
            .statusCode(403);

        // 0 times: ServiceToServiceAuthorizationFilter denied this before the gateway
        // ever proxied it downstream to WireMock (the "would succeed" stub above never fires).
        WIREMOCK.verify(0, getRequestedFor(urlPathEqualTo("/api/v1/service-b/restricted")));

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            Map<String, Object> row = findAuditRow(adminToken, traceId);
            assertThat(row.get("initiatorClient")).isEqualTo("service-a");
            assertThat(row.get("targetService")).isEqualTo("service-b");
            assertThat(row.get("decisionEffect")).isEqualTo("DENY");
            assertThat(((Number) row.get("statusCode")).intValue()).isEqualTo(403);
        });
    }

    private Map<String, Object> findAuditRow(String adminToken, String traceId) {
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
        return matching.get(0);
    }
}
