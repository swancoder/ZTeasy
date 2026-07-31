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
 * E2E test for the MCP proxy (ADR-009): the full {@code GET /sse} →
 * {@code POST /message} → SSE-injection round trip.
 *
 * <p>The unit tests ({@code McpSessionManagerTest}, {@code DummyMcpPolicyEngineTest})
 * exercise those two components in isolation. This test exercises the actual
 * cross-request session-injection mechanism — the reason the MCP proxy exists as
 * a plain WebFlux router rather than a Gateway route in the first place — against
 * the real running gateway. Flagged as missing in ADR-009 and {@code docs/SPEC.md}
 * §8.4/§9.3.
 *
 * <p>Chain exercised: Keycloak JWT → {@code GET /sse} (session opened, handshake
 * event received) → {@code POST /message} (policy check) → result injected back
 * into the still-open SSE stream. The backend MCP server is replaced by WireMock,
 * same pattern {@link HappyPathIT} uses for service-a/b.
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
    @DisplayName("Denied tool call: backend never called, denial injected into the SSE stream")
    void deniedToolCall_isInjectedIntoSseStream_backendNeverCalled() {
        String token = getAdminToken();
        AtomicReference<String> sessionId = new AtomicReference<>();

        StepVerifier.create(openSseStream(token))
                .assertNext(handshake -> {
                    assertThat(handshake.event()).isEqualTo("endpoint");
                    sessionId.set(extractSessionId(handshake.data()));
                })
                .then(() -> postMessage(token, sessionId.get(), 42, "export_all_data"))
                .assertNext(result -> {
                    assertThat(result.event()).isEqualTo("message");
                    JsonNode json = readTree(result.data());
                    assertThat(json.get("id").asInt()).isEqualTo(42);
                    assertThat(json.get("result").get("isError").asBoolean()).isTrue();
                    assertThat(json.get("result").toString()).contains("export_all_data");
                })
                .thenCancel()
                .verify(Duration.ofSeconds(10));

        WIREMOCK.verify(0, postRequestedFor(urlPathEqualTo("/message")));
    }

    @Test
    @DisplayName("Allowed tool call: forwarded to the backend, result injected into the SSE stream")
    void allowedToolCall_isForwardedToBackend_andResultInjectedIntoSseStream() {
        WIREMOCK.stubFor(post(urlPathEqualTo("/message"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"jsonrpc":"2.0","id":7,
                                 "result":{"content":[{"type":"text","text":"3 contacts"}]},
                                 "error":null}
                                """)));

        String token = getAdminToken();
        AtomicReference<String> sessionId = new AtomicReference<>();

        StepVerifier.create(openSseStream(token))
                .assertNext(handshake -> sessionId.set(extractSessionId(handshake.data())))
                .then(() -> postMessage(token, sessionId.get(), 7, "get_contacts"))
                .assertNext(result -> {
                    JsonNode json = readTree(result.data());
                    assertThat(json.get("id").asInt()).isEqualTo(7);
                    assertThat(json.get("result").toString()).contains("3 contacts");
                    assertThat(json.get("error").isNull()).isTrue();
                })
                .thenCancel()
                .verify(Duration.ofSeconds(10));

        WIREMOCK.verify(1, postRequestedFor(urlPathEqualTo("/message"))
                .withRequestBody(matchingJsonPath("$.params.name", equalTo("get_contacts"))));
    }

    @Test
    @DisplayName("POST /message with an unknown sessionId → 400, nothing to inject into")
    void unknownSessionId_returns400() {
        String token = getAdminToken();

        given()
            .baseUri("http://localhost:" + gatewayPort)
            .header("Authorization", "Bearer " + token)
            .contentType("application/json")
            .body(jsonRpcBody(1, "get_contacts"))
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
