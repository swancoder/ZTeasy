package com.zte.gateway.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * E2E test for the MCP proxy (ADR-009, ADR-011): the full {@code GET /sse} →
 * {@code POST /message} → SSE-injection round trip, now exercising real
 * {@code YamlMcpPolicyEngine} enforcement (superseding Stage 9/10's dead-end
 * stub — see ADR-011).
 *
 * <p>Chain exercised: Keycloak client-credentials JWT (agent-a / agent-b, per
 * ADR-010) → {@code GET /sse} (session opened, handshake event received) →
 * {@code POST /message} (policy check against the default
 * {@code zte-policies.yaml} rule set) → result injected back into the still-open
 * SSE stream. Allowed calls are forwarded to the backend MCP server, stood in
 * by WireMock; denied calls never reach it.
 */
@DisplayName("MCP Proxy — GET /sse + POST /message round trip")
class McpProxyIT extends BaseZteIntegrationTest {

    private static final Pattern SESSION_ID_PATTERN = Pattern.compile("sessionId=([\\w-]+)");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private WebTestClient webTestClient;

    @BeforeEach
    void setUpWebTestClient() {
        webTestClient = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + gatewayPort)
                .responseTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Test
    @DisplayName("Held tool call (Stage 1, ADR-019): 🟡 SSE status, backend untouched until an admin approves it")
    void heldTool_emitsHeldStatus_thenAdminApprove_forwardsToBackend() {
        // The SSE stream is cancelled right after the "held" event (see thenCancel()
        // below), same as every other test in this class — by the time the admin
        // approval REST calls below run, the session has already closed. That's a
        // deliberately exercised path too: PendingApprovalService still executes and
        // audits the decision, it just can't push the result back into a closed
        // session (see PendingApproval's Javadoc) — covered at the unit level by
        // PendingApprovalServiceTest's still-open-session case.
        WIREMOCK.stubFor(post(urlPathEqualTo("/message"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"jsonrpc":"2.0","id":5,
                                 "result":{"content":[{"type":"text","text":"draft sent"}]},
                                 "error":null}
                                """)));

        String token = getAgentToken("crm-account-health-emea-01", IT_CRM_AGENT_SECRET);
        AtomicReference<String> sessionId = new AtomicReference<>();
        AtomicReference<String> approvalId = new AtomicReference<>();

        StepVerifier.create(openSseStream(token))
                .assertNext(handshake -> sessionId.set(extractSessionId(handshake.data())))
                .then(() -> postMessage(token, sessionId.get(), 5, "send_email"))
                .assertNext(result -> {
                    JsonNode json = readTree(result.data());
                    assertThat(json.get("id").asInt()).isEqualTo(5);
                    assertThat(json.get("result").get("status").asText()).isEqualTo("held");
                    approvalId.set(json.get("result").get("approvalId").asText());
                })
                .thenCancel()
                .verify(Duration.ofSeconds(10));

        WIREMOCK.verify(0, postRequestedFor(urlPathEqualTo("/message")));

        given()
            .baseUri("http://localhost:" + gatewayPort)
            .header("Authorization", "Bearer " + getAdminToken())
        .when()
            .get("/api/v1/admin/approvals")
        .then()
            .statusCode(200)
            .body("id", org.hamcrest.Matchers.hasItem(approvalId.get()));

        given()
            .baseUri("http://localhost:" + gatewayPort)
            .header("Authorization", "Bearer " + getAdminToken())
        .when()
            .post("/api/v1/admin/approvals/" + approvalId.get() + "/approve")
        .then()
            .statusCode(200)
            .body("status", org.hamcrest.Matchers.equalTo("APPROVED"));

        WIREMOCK.verify(1, postRequestedFor(urlPathEqualTo("/message"))
                .withRequestBody(matchingJsonPath("$.params.name", equalTo("send_email"))));
    }

    // ── Stage 3 (ADR-020): ACAP argument/field-level tightening ─────────────
    // Same agent, same tool (read_contacts) — different arguments must produce
    // different decisions. Mirrors the demo script's own RED/GREEN light cases.

    @Test
    @DisplayName("ACAP 🟢: read_contacts(EMEA) with only allowed fields — forwarded to backend")
    void acapAgent_readContacts_correctTerritoryAndAllowedFields_isAllowed() {
        WIREMOCK.stubFor(post(urlPathEqualTo("/message"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"jsonrpc":"2.0","id":10,"result":{"content":[{"type":"text","text":"3 contacts"}]},"error":null}
                                """)));

        String token = getAgentToken("crm-account-health-emea-01", IT_CRM_AGENT_SECRET);
        AtomicReference<String> sessionId = new AtomicReference<>();

        StepVerifier.create(openSseStream(token))
                .assertNext(handshake -> sessionId.set(extractSessionId(handshake.data())))
                .then(() -> postMessage(token, sessionId.get(), 10, "read_contacts",
                        "{\"territory\":\"EMEA\",\"fields\":[\"company\",\"lifecyclestage\"]}"))
                .assertNext(result -> {
                    JsonNode json = readTree(result.data());
                    assertThat(json.get("result").toString()).contains("3 contacts");
                })
                .thenCancel()
                .verify(Duration.ofSeconds(10));

        WIREMOCK.verify(1, postRequestedFor(urlPathEqualTo("/message")));
    }

    @Test
    @DisplayName("ACAP 🔴: read_contacts(NA) — same tool, wrong territory, denied by the ACAP profile, backend never called")
    void acapAgent_readContacts_wrongTerritory_isDenied_backendNeverCalled() {
        String token = getAgentToken("crm-account-health-emea-01", IT_CRM_AGENT_SECRET);
        AtomicReference<String> sessionId = new AtomicReference<>();

        StepVerifier.create(openSseStream(token))
                .assertNext(handshake -> sessionId.set(extractSessionId(handshake.data())))
                .then(() -> postMessage(token, sessionId.get(), 11, "read_contacts", "{\"territory\":\"NA\"}"))
                .assertNext(result -> {
                    JsonNode json = readTree(result.data());
                    assertThat(json.get("result").get("isError").asBoolean()).isTrue();
                    assertThat(json.get("result").toString()).contains("read_outside_territory");
                })
                .thenCancel()
                .verify(Duration.ofSeconds(10));

        WIREMOCK.verify(0, postRequestedFor(urlPathEqualTo("/message")));
    }

    @Test
    @DisplayName("ACAP 🔴: read_contacts(EMEA, fields=[id_number]) — same tool, disallowed field, denied (data minimization)")
    void acapAgent_readContacts_disallowedField_isDenied_backendNeverCalled() {
        String token = getAgentToken("crm-account-health-emea-01", IT_CRM_AGENT_SECRET);
        AtomicReference<String> sessionId = new AtomicReference<>();

        StepVerifier.create(openSseStream(token))
                .assertNext(handshake -> sessionId.set(extractSessionId(handshake.data())))
                .then(() -> postMessage(token, sessionId.get(), 12, "read_contacts",
                        "{\"territory\":\"EMEA\",\"fields\":[\"id_number\"]}"))
                .assertNext(result -> {
                    JsonNode json = readTree(result.data());
                    assertThat(json.get("result").get("isError").asBoolean()).isTrue();
                    assertThat(json.get("result").toString()).contains("fields.deny");
                })
                .thenCancel()
                .verify(Duration.ofSeconds(10));

        WIREMOCK.verify(0, postRequestedFor(urlPathEqualTo("/message")));
    }

    @Test
    @DisplayName("ACAP 🔴: update_deal — granted coarsely, denied by the read-only ACAP profile, backend never called")
    void acapAgent_updateDeal_isDenied_byReadOnlyProfile_backendNeverCalled() {
        String token = getAgentToken("crm-account-health-emea-01", IT_CRM_AGENT_SECRET);
        AtomicReference<String> sessionId = new AtomicReference<>();

        StepVerifier.create(openSseStream(token))
                .assertNext(handshake -> sessionId.set(extractSessionId(handshake.data())))
                .then(() -> postMessage(token, sessionId.get(), 13, "update_deal"))
                .assertNext(result -> {
                    JsonNode json = readTree(result.data());
                    assertThat(json.get("result").get("isError").asBoolean()).isTrue();
                    assertThat(json.get("result").toString()).contains("change_record");
                })
                .thenCancel()
                .verify(Duration.ofSeconds(10));

        WIREMOCK.verify(0, postRequestedFor(urlPathEqualTo("/message")));
    }

    @Test
    @DisplayName("No Authorization header — GET /sse and POST /message both 401")
    void noToken_sseAndMessage_return401() {
        given()
            .baseUri("http://localhost:" + gatewayPort)
        .when()
            .get("/sse")
        .then()
            .statusCode(401);

        given()
            .baseUri("http://localhost:" + gatewayPort)
            .contentType("application/json")
            .body(jsonRpcBody(99, "get_deals"))
        .when()
            .post("/message?sessionId=irrelevant-without-a-token")
        .then()
            .statusCode(401);
    }

    @Test
    @DisplayName("POST /message with an unknown sessionId → 400, nothing to inject into")
    void unknownSessionId_returns400() {
        String token = getAgentToken("crm-account-health-emea-01", IT_CRM_AGENT_SECRET);

        given()
            .baseUri("http://localhost:" + gatewayPort)
            .header("Authorization", "Bearer " + token)
            .contentType("application/json")
            .body(jsonRpcBody(1, "get_deals"))
        .when()
            .post("/message?sessionId=does-not-exist")
        .then()
            .statusCode(400);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private Flux<ServerSentEvent<String>> openSseStream(String token) {
        return webTestClient.get()
                .uri("/sse")
                .header("Authorization", "Bearer " + token)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus().isOk()
                .returnResult(new ParameterizedTypeReference<ServerSentEvent<String>>() {})
                .getResponseBody();
    }

    private void postMessage(String token, String sessionId, int id, String toolName) {
        postMessage(token, sessionId, id, toolName, "{}");
    }

    /** Stage 3 (ADR-020): arguments-carrying overload, for exercising ACAP territory/field checks. */
    private void postMessage(String token, String sessionId, int id, String toolName, String argumentsJson) {
        given()
            .baseUri("http://localhost:" + gatewayPort)
            .header("Authorization", "Bearer " + token)
            .contentType("application/json")
            .body(jsonRpcBody(id, toolName, argumentsJson))
        .when()
            .post("/message?sessionId=" + sessionId)
        .then()
            .statusCode(202);
    }

    private String jsonRpcBody(int id, String toolName) {
        return jsonRpcBody(id, toolName, "{}");
    }

    private String jsonRpcBody(int id, String toolName, String argumentsJson) {
        return """
                {"jsonrpc":"2.0","id":%d,"method":"tools/call","params":{"name":"%s","arguments":%s}}
                """.formatted(id, toolName, argumentsJson);
    }

    private String extractSessionId(String endpointEventData) {
        Matcher m = SESSION_ID_PATTERN.matcher(endpointEventData);
        assertThat(m.find())
                .as("endpoint event data should contain sessionId: %s", endpointEventData)
                .isTrue();
        return m.group(1);
    }

    private JsonNode readTree(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse SSE event data as JSON: " + json, e);
        }
    }
}
