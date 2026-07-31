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

import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * E2E test for the MCP proxy (ADR-009, ADR-010): the full {@code GET /sse} →
 * {@code POST /message} → SSE-injection round trip.
 *
 * <p>The unit tests ({@code McpSessionManagerTest}, {@code DummyMcpPolicyEngineTest})
 * exercise those two components in isolation. This test exercises the actual
 * cross-request session-injection mechanism — the reason the MCP proxy exists as
 * a plain WebFlux router rather than a Gateway route in the first place — against
 * the real running gateway, using the client-credentials tokens Agent A / Agent B
 * actually authenticate with (see ADR-010).
 *
 * <p>Chain exercised: Keycloak client-credentials JWT → {@code GET /sse} (session
 * opened, handshake event received) → {@code POST /message} (JWT validated,
 * clientId extracted from {@code azp}) → stub result injected back into the
 * still-open SSE stream. As of Stage 9 (ADR-010) the gateway is a deliberate
 * dead-end: no policy check, no backend call — so unlike the Stage 8 version of
 * this test, every authenticated call gets the same stub outcome regardless of
 * tool name, and WireMock (standing in for a real backend) is asserted to never
 * be called at all.
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
    @DisplayName("Agent A (client credentials): stub response names agent-a, backend never called")
    void agentA_getsStubResponse_namingItself_backendNeverCalled() {
        assertAgentGetsStubResponse("agent-a", "agent-a-secret-dev-only", "get_deals", 1);
    }

    @Test
    @DisplayName("Agent B (client credentials): stub response names agent-b, backend never called")
    void agentB_getsStubResponse_namingItself_backendNeverCalled() {
        assertAgentGetsStubResponse("agent-b", "agent-b-secret-dev-only", "update_deal_stage", 2);
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
        String token = getAgentToken("agent-a", "agent-a-secret-dev-only");

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

    // ── shared assertion ─────────────────────────────────────────────────────

    private void assertAgentGetsStubResponse(String clientId, String clientSecret, String toolName, int requestId) {
        String token = getAgentToken(clientId, clientSecret);
        AtomicReference<String> sessionId = new AtomicReference<>();

        StepVerifier.create(openSseStream(token))
                .assertNext(handshake -> {
                    assertThat(handshake.event()).isEqualTo("endpoint");
                    sessionId.set(extractSessionId(handshake.data()));
                })
                .then(() -> postMessage(token, sessionId.get(), requestId, toolName))
                .assertNext(result -> {
                    assertThat(result.event()).isEqualTo("message");
                    JsonNode json = readTree(result.data());
                    assertThat(json.get("id").asInt()).isEqualTo(requestId);
                    assertThat(json.get("result").get("isError").asBoolean()).isFalse();
                    assertThat(json.get("result").toString()).contains(clientId);
                })
                .thenCancel()
                .verify(Duration.ofSeconds(10));

        WIREMOCK.verify(0, postRequestedFor(urlPathEqualTo("/message")));
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
        given()
            .baseUri("http://localhost:" + gatewayPort)
            .header("Authorization", "Bearer " + token)
            .contentType("application/json")
            .body(jsonRpcBody(id, toolName))
        .when()
            .post("/message?sessionId=" + sessionId)
        .then()
            .statusCode(202);
    }

    private String jsonRpcBody(int id, String toolName) {
        return """
                {"jsonrpc":"2.0","id":%d,"method":"tools/call","params":{"name":"%s","arguments":{}}}
                """.formatted(id, toolName);
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
