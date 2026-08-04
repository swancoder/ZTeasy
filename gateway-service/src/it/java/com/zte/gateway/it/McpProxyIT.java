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
    @DisplayName("Agent A calling its granted tool (get_deals): forwarded to backend, result injected into SSE")
    void agentA_allowedTool_isForwardedToBackend_andResultInjectedIntoSseStream() {
        WIREMOCK.stubFor(post(urlPathEqualTo("/message"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"jsonrpc":"2.0","id":1,
                                 "result":{"content":[{"type":"text","text":"3 deals"}]},
                                 "error":null}
                                """)));

        String token = getAgentToken("agent-a", "agent-a-secret-dev-only");
        AtomicReference<String> sessionId = new AtomicReference<>();

        StepVerifier.create(openSseStream(token))
                .assertNext(handshake -> {
                    assertThat(handshake.event()).isEqualTo("endpoint");
                    sessionId.set(extractSessionId(handshake.data()));
                })
                .then(() -> postMessage(token, sessionId.get(), 1, "get_deals"))
                .assertNext(result -> {
                    assertThat(result.event()).isEqualTo("message");
                    JsonNode json = readTree(result.data());
                    assertThat(json.get("id").asInt()).isEqualTo(1);
                    assertThat(json.get("result").toString()).contains("3 deals");
                    assertThat(json.get("error").isNull()).isTrue();
                })
                .thenCancel()
                .verify(Duration.ofSeconds(10));

        WIREMOCK.verify(1, postRequestedFor(urlPathEqualTo("/message"))
                .withRequestBody(matchingJsonPath("$.params.name", equalTo("get_deals"))));
    }

    @Test
    @DisplayName("Agent A calling a tool it has no grant for: denial injected into SSE, backend never called")
    void agentA_toolWithNoGrant_isDenied_backendNeverCalled() {
        String token = getAgentToken("agent-a", "agent-a-secret-dev-only");
        AtomicReference<String> sessionId = new AtomicReference<>();

        StepVerifier.create(openSseStream(token))
                .assertNext(handshake -> sessionId.set(extractSessionId(handshake.data())))
                .then(() -> postMessage(token, sessionId.get(), 2, "update_deal_stage"))
                .assertNext(result -> {
                    JsonNode json = readTree(result.data());
                    assertThat(json.get("id").asInt()).isEqualTo(2);
                    assertThat(json.get("result").get("isError").asBoolean()).isTrue();
                })
                .thenCancel()
                .verify(Duration.ofSeconds(10));

        WIREMOCK.verify(0, postRequestedFor(urlPathEqualTo("/message")));
    }

    @Test
    @DisplayName("Agent B calling a destructive-shaped tool: denied by the deny-list rule, backend never called")
    void agentB_destructiveTool_isDenied_backendNeverCalled() {
        String token = getAgentToken("agent-b", "agent-b-secret-dev-only");
        AtomicReference<String> sessionId = new AtomicReference<>();

        StepVerifier.create(openSseStream(token))
                .assertNext(handshake -> sessionId.set(extractSessionId(handshake.data())))
                .then(() -> postMessage(token, sessionId.get(), 3, "delete_deal"))
                .assertNext(result -> {
                    JsonNode json = readTree(result.data());
                    assertThat(json.get("id").asInt()).isEqualTo(3);
                    assertThat(json.get("result").get("isError").asBoolean()).isTrue();
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
