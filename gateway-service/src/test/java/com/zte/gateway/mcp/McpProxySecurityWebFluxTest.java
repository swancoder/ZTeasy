package com.zte.gateway.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zte.auth.SecurityConfig;
import com.zte.gateway.mcp.audit.McpAuditService;
import com.zte.gateway.mcp.mask.DataMaskingFilter;
import com.zte.gateway.mcp.policy.McpPolicyEngine;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;

/**
 * Slice test for the MCP proxy's security boundary (ADR-010).
 *
 * <p>Verifies that the shared, auto-configured {@code auth-library} SecurityConfig
 * (not a gateway-local one — see ADR-010's rationale for not adding a competing
 * chain) actually gates {@code GET /sse} and {@code POST /message}, and that an
 * authenticated dead-end call never reaches {@link McpPolicyEngine} or
 * {@link McpBackendClient}. Collaborators are Mockito mocks via {@code @MockBean},
 * so this runs without Docker/Testcontainers — contrast with {@code McpProxyIT}
 * in {@code src/it}, which exercises the same endpoints against a real Keycloak.
 *
 * <p>{@code SecurityConfig} must be imported explicitly: {@code @WebFluxTest}
 * only auto-includes Boot's own officially recognized auto-configurations for
 * the web slice, not a separate module's custom {@code @AutoConfiguration}
 * (here, auth-library's {@code ZteSecurityAutoConfiguration}) — without this
 * import, Spring Security falls back to its generic default reactive chain,
 * which (unlike ours) doesn't disable CSRF, and every POST here would 403
 * before ever reaching authentication.
 */
@WebFluxTest(controllers = McpProxyHandler.class)
@Import({McpRouterConfig.class, SecurityConfig.class})
class McpProxySecurityWebFluxTest {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private McpSessionManager sessionManager;

    @MockBean
    private McpPolicyEngine policyEngine;

    @MockBean
    private McpAuditService auditService;

    @MockBean
    private DataMaskingFilter dataMaskingFilter;

    @MockBean
    private McpBackendClient backendClient;

    @Test
    void sse_withoutToken_returns401() {
        webTestClient.get().uri("/sse")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void message_withoutToken_returns401() {
        webTestClient.post().uri("/message?sessionId=whatever")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void sse_withValidJwt_returns200() {
        when(sessionManager.open(any())).thenReturn(Flux.empty());

        webTestClient.mutateWith(mockJwt())
                .get().uri("/sse")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void message_withValidJwtButUnknownSession_returns400_andNeverTouchesPolicyOrBackend() {
        when(sessionManager.exists(any())).thenReturn(false);

        webTestClient.mutateWith(mockJwt())
                .post().uri("/message?sessionId=does-not-exist")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(jsonRpcBody(1))
                .exchange()
                .expectStatus().isBadRequest();

        verifyNoInteractions(policyEngine, backendClient);
    }

    @Test
    void message_withValidJwtAndKnownSession_returns202_emitsStub_neverCallsPolicyOrBackend() {
        when(sessionManager.exists("session-1")).thenReturn(true);

        webTestClient.mutateWith(mockJwt().jwt(jwt -> jwt.claim("azp", "agent-a")))
                .post().uri("/message?sessionId=session-1")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(jsonRpcBody(7))
                .exchange()
                .expectStatus().isAccepted();

        verifyNoInteractions(policyEngine, backendClient);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<ServerSentEvent<String>> captor = ArgumentCaptor.forClass(ServerSentEvent.class);
        verify(sessionManager).emit(eq("session-1"), captor.capture());

        JsonNode json = readTree(captor.getValue().data());
        assertThat(json.get("id").asInt()).isEqualTo(7);
        assertThat(json.get("result").get("isError").asBoolean()).isFalse();
        assertThat(json.get("result").toString()).contains("agent-a");
    }

    private String jsonRpcBody(int id) {
        return """
                {"jsonrpc":"2.0","id":%d,"method":"tools/call","params":{"name":"get_deals","arguments":{}}}
                """.formatted(id);
    }

    private JsonNode readTree(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse SSE event data as JSON: " + json, e);
        }
    }
}
