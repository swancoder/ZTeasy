package com.zte.gateway.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zte.auth.SecurityConfig;
import com.zte.gateway.mcp.approval.PendingApproval;
import com.zte.gateway.mcp.approval.PendingApprovalService;
import com.zte.gateway.mcp.audit.McpAuditService;
import com.zte.gateway.mcp.model.JsonRpcResponse;
import com.zte.gateway.mcp.policy.McpPolicyEngine;
import com.zte.gateway.mcp.policy.PolicyDecision;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;

/**
 * Slice test for the MCP proxy's security boundary and policy enforcement
 * (ADR-011, superseding ADR-010's dead-end stub — Stage 9/10's assertion that
 * the policy engine and backend are never touched no longer holds).
 *
 * <p>Verifies that the shared, auto-configured {@code auth-library} SecurityConfig
 * (not a gateway-local one — see ADR-010's rationale for not adding a competing
 * chain) actually gates {@code GET /sse} and {@code POST /message}, and that an
 * authenticated call is now routed through {@link McpPolicyEngine} to a denial,
 * a hold ({@link PendingApprovalService}, Stage 1/ADR-019), or {@link
 * McpForwardService} (allow path).
 * Collaborators are Mockito mocks via {@code @MockBean}, so this runs without
 * Docker/Testcontainers — contrast with {@code McpProxyIT} in {@code src/it},
 * which exercises the same endpoints against a real Keycloak and the real
 * {@code YamlMcpPolicyEngine}.
 *
 * <p>{@code SecurityConfig} must be imported explicitly: {@code @WebFluxTest}
 * only auto-includes Boot's own officially recognized auto-configurations for
 * the web slice, not a separate module's custom {@code @AutoConfiguration}
 * (here, auth-library's {@code ZteSecurityAutoConfiguration}) — without this
 * import, Spring Security falls back to its generic default reactive chain,
 * which (unlike ours) doesn't disable CSRF, and every POST here would 403
 * before ever reaching authentication.
 *
 * <p>{@code zte.mtls.enabled=false} (ADR-018): {@code MtlsEnforcementWebFilter}
 * is a plain {@code WebFilter} too, so it's auto-detected into this slice just
 * like {@code AdminAuthorizationFilter}/{@code RequestAuditFilter} below — but
 * {@code WebTestClient} here never performs a real TLS handshake, so there's
 * no {@code SslInfo} to present regardless of what the test sends. This test
 * exists to verify the JWT/policy boundary, not the mTLS one (that's
 * {@code MtlsEnforcementWebFilterTest}), so the property is disabled the same
 * way {@code application-it.yml} disables it for the same reason.
 */
@WebFluxTest(controllers = McpProxyHandler.class)
@Import({McpRouterConfig.class, SecurityConfig.class})
@TestPropertySource(properties = "zte.mtls.enabled=false")
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
    private McpForwardService forwardService;

    @MockBean
    private PendingApprovalService pendingApprovalService;

    // AdminAuthorizationFilter (ADR-012) and RequestAuditFilter (ADR-013) are both
    // plain WebFilters, so @WebFluxTest's type-based auto-detection pulls them into
    // this slice too even though this test never exercises /api/v1/admin/**. Their
    // constructor deps just need to exist as mocks.
    @MockBean
    private com.zte.gateway.policy.def.PolicyDefinitionStore policyDefinitionStore;

    @MockBean
    private com.zte.gateway.policy.def.PolicyMatcher policyMatcher;

    // Stage 31 (ADR-031): AdminAuthorizationFilter now depends on the
    // activation-aware evaluator instead of PolicyMatcher directly.
    @MockBean
    private com.zte.gateway.policy.activation.ActivePolicyEvaluator activePolicyEvaluator;

    @MockBean
    private com.zte.gateway.audit.RequestLogAuditService requestLogAuditService;

    @MockBean
    private com.zte.gateway.filter.AuditExclusionProperties auditExclusionProperties;

    @MockBean
    private com.zte.gateway.inventory.HealthTelemetryService healthTelemetryService;

    @BeforeEach
    void stubAuditExclusions() {
        // RequestAuditFilter's doFinally unconditionally consults this list — a bare
        // mock returns null, which would NPE. /sse and /message aren't excluded by
        // default, so an empty list here matches real behavior for this test's paths.
        when(auditExclusionProperties.getExcludedPathPrefixes()).thenReturn(java.util.List.of());
    }

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
                .bodyValue(jsonRpcBody(1, "get_deals"))
                .exchange()
                .expectStatus().isBadRequest();

        verifyNoInteractions(policyEngine, forwardService);
    }

    @Test
    void message_deniedByPolicy_emitsDenial_neverCallsBackend() {
        when(sessionManager.exists("session-1")).thenReturn(true);
        when(policyEngine.evaluate(eq("agent-a"), eq("delete_deal"), any()))
                .thenReturn(PolicyDecision.deny("denied by test policy"));

        webTestClient.mutateWith(mockJwt().jwt(jwt -> jwt.claim("azp", "agent-a")))
                .post().uri("/message?sessionId=session-1")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(jsonRpcBody(7, "delete_deal"))
                .exchange()
                .expectStatus().isAccepted();

        verifyNoInteractions(forwardService);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<ServerSentEvent<String>> captor = ArgumentCaptor.forClass(ServerSentEvent.class);
        verify(sessionManager).emit(eq("session-1"), captor.capture());

        JsonNode json = readTree(captor.getValue().data());
        assertThat(json.get("id").asInt()).isEqualTo(7);
        assertThat(json.get("result").get("isError").asBoolean()).isTrue();
        assertThat(json.get("result").toString()).contains("denied by test policy");
    }

    @Test
    void message_allowedByPolicy_forwardsToBackend_emitsResult() {
        when(sessionManager.exists("session-1")).thenReturn(true);
        when(policyEngine.evaluate(eq("agent-a"), eq("get_deals"), any()))
                .thenReturn(PolicyDecision.allow());
        JsonRpcResponse backendResponse = JsonRpcResponse.success(7, Map.of("content", "3 deals"));
        when(forwardService.execute(anyString(), any())).thenReturn(Mono.just(backendResponse));

        webTestClient.mutateWith(mockJwt().jwt(jwt -> jwt.claim("azp", "agent-a")))
                .post().uri("/message?sessionId=session-1")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(jsonRpcBody(7, "get_deals"))
                .exchange()
                .expectStatus().isAccepted();

        verify(forwardService).execute(anyString(), any());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<ServerSentEvent<String>> captor = ArgumentCaptor.forClass(ServerSentEvent.class);
        verify(sessionManager).emit(eq("session-1"), captor.capture());

        JsonNode json = readTree(captor.getValue().data());
        assertThat(json.get("id").asInt()).isEqualTo(7);
        assertThat(json.get("result").get("content").asText()).isEqualTo("3 deals");
    }

    @Test
    void message_heldByPolicy_persistsApproval_emitsHeldStatus_neverCallsBackend() {
        when(sessionManager.exists("session-1")).thenReturn(true);
        when(policyEngine.evaluate(eq("crm-account-health-emea-01"), eq("send_email"), any()))
                .thenReturn(PolicyDecision.hold("held by test policy"));
        PendingApproval approval = new PendingApproval(UUID.fromString("11111111-1111-1111-1111-111111111111"),
                "session-1", "crm-account-health-emea-01", "send_email", "7", "{}", null, "held by test policy",
                "PENDING", Instant.now(), null, null, null, null, null, null);
        when(pendingApprovalService.hold(eq("session-1"), eq("crm-account-health-emea-01"), eq("send_email"), any(),
                eq("held by test policy"), any(), any(), any(), any())).thenReturn(Mono.just(approval));

        webTestClient.mutateWith(mockJwt().jwt(jwt -> jwt.claim("azp", "crm-account-health-emea-01")))
                .post().uri("/message?sessionId=session-1")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(jsonRpcBody(7, "send_email"))
                .exchange()
                .expectStatus().isAccepted();

        verifyNoInteractions(forwardService);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<ServerSentEvent<String>> captor = ArgumentCaptor.forClass(ServerSentEvent.class);
        verify(sessionManager).emit(eq("session-1"), captor.capture());

        JsonNode json = readTree(captor.getValue().data());
        assertThat(json.get("id").asInt()).isEqualTo(7);
        assertThat(json.get("result").get("status").asText()).isEqualTo("held");
        assertThat(json.get("result").get("approvalId").asText()).isEqualTo("11111111-1111-1111-1111-111111111111");
    }

    private String jsonRpcBody(int id, String toolName) {
        return """
                {"jsonrpc":"2.0","id":%d,"method":"tools/call","params":{"name":"%s","arguments":{}}}
                """.formatted(id, toolName);
    }

    private JsonNode readTree(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse SSE event data as JSON: " + json, e);
        }
    }
}
