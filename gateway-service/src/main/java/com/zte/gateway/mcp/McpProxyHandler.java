package com.zte.gateway.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zte.gateway.mcp.audit.McpAuditEvent;
import com.zte.gateway.mcp.audit.McpAuditService;
import com.zte.gateway.mcp.mask.DataMaskingFilter;
import com.zte.gateway.mcp.model.JsonRpcRequest;
import com.zte.gateway.mcp.model.JsonRpcResponse;
import com.zte.gateway.mcp.policy.McpPolicyEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Handlers for the MCP proxy endpoints ({@code GET /sse}, {@code POST /message}).
 *
 * <p>Session lifecycle: a client opens {@code GET /sse}; this gateway generates a
 * {@code sessionId}, registers it with {@link McpSessionManager}, and pushes an
 * {@code endpoint} SSE event containing {@code /message?sessionId=<id>} — the
 * client's cue for where to POST subsequent JSON-RPC calls (this is the standard
 * MCP HTTP+SSE handshake, not a ZTeasy-specific invention).
 *
 * <p><b>Stage 9 (ADR-010) — dead-end stub:</b> {@code POST /message?sessionId=<id>}
 * only validates the caller's JWT and extracts the client identity; it does
 * <em>not</em> call {@link McpPolicyEngine} or {@link McpBackendClient}. A stub
 * success response naming the authenticated client is injected into the SSE
 * stream instead. The transport contract from Stage 8 is unchanged — the POST
 * still only ever returns 202 Accepted, and the real answer still travels over
 * SSE — only what gets computed has changed. {@code policyEngine},
 * {@code backendClient}, and {@code dataMaskingFilter} stay wired here
 * (unused for now) so re-enabling them is a one-method change in {@link #process}
 * once per-agent authorization is ready to be exercised end-to-end.
 */
@Component
public class McpProxyHandler {

    private static final Logger log = LoggerFactory.getLogger(McpProxyHandler.class);
    private static final String PROCESS_ID = String.valueOf(ProcessHandle.current().pid());

    private final McpSessionManager sessionManager;
    private final McpPolicyEngine policyEngine;
    private final McpAuditService auditService;
    private final DataMaskingFilter dataMaskingFilter;
    private final McpBackendClient backendClient;
    private final ObjectMapper objectMapper;

    public McpProxyHandler(McpSessionManager sessionManager,
                            McpPolicyEngine policyEngine,
                            McpAuditService auditService,
                            DataMaskingFilter dataMaskingFilter,
                            McpBackendClient backendClient,
                            ObjectMapper objectMapper) {
        this.sessionManager = sessionManager;
        this.policyEngine = policyEngine;
        this.auditService = auditService;
        this.dataMaskingFilter = dataMaskingFilter;
        this.backendClient = backendClient;
        this.objectMapper = objectMapper;
    }

    public Mono<ServerResponse> handleSse(ServerRequest request) {
        return currentAgentId(request).flatMap(agentId -> {
            String sessionId = UUID.randomUUID().toString();
            log.info("MCP SSE session opened sessionId={} agentId={}", sessionId, agentId);

            Flux<ServerSentEvent<String>> handshake = Flux.just(
                    ServerSentEvent.<String>builder()
                            .event("endpoint")
                            .data("/message?sessionId=" + sessionId)
                            .build());

            Flux<ServerSentEvent<String>> stream = Flux.concat(handshake, sessionManager.open(sessionId));

            return ServerResponse.ok()
                    .contentType(MediaType.TEXT_EVENT_STREAM)
                    .body(stream, new ParameterizedTypeReference<ServerSentEvent<String>>() {});
        });
    }

    public Mono<ServerResponse> handleMessage(ServerRequest request) {
        String sessionId = request.queryParam("sessionId").orElse(null);
        if (sessionId == null || !sessionManager.exists(sessionId)) {
            return ServerResponse.badRequest()
                    .bodyValue(Map.of("error", "Unknown or missing sessionId — call GET /sse first"));
        }

        return currentAgentId(request)
                .flatMap(agentId -> request.bodyToMono(JsonRpcRequest.class)
                        .flatMap(rpc -> process(sessionId, agentId, rpc)))
                .then(ServerResponse.accepted().build());
    }

    /**
     * Dead-end stub (Stage 9 / ADR-010): logs the authenticated client and emits
     * a stub success response into the caller's SSE session. Deliberately does
     * not call {@link #policyEngine} or {@link #backendClient} — see the class
     * Javadoc for why, and what re-enabling them looks like.
     */
    private Mono<Void> process(String sessionId, String agentId, JsonRpcRequest rpc) {
        String toolName = rpc.toolName();
        log.info("MCP STUB sessionId={} clientId={} tool={}", sessionId, agentId, toolName);
        auditService.record(new McpAuditEvent(PROCESS_ID, agentId, toolName, "STUBBED", Instant.now()));
        return emit(sessionId, JsonRpcResponse.stubbed(rpc.id(), agentId));
    }

    private Mono<Void> emit(String sessionId, JsonRpcResponse response) {
        return Mono.fromCallable(() -> objectMapper.writeValueAsString(response))
                .doOnNext(json -> sessionManager.emit(sessionId,
                        ServerSentEvent.<String>builder().event("message").data(json).build()))
                .then();
    }

    /**
     * The calling client's identity: prefers {@code azp} (the OAuth2 client_id —
     * what a client-credentials-flow agent token carries, and the same claim
     * {@code RequestAuditFilter} already uses elsewhere in this gateway), falling
     * back to {@code sub} for tokens that don't carry {@code azp}.
     */
    private Mono<String> currentAgentId(ServerRequest request) {
        return request.principal()
                .cast(JwtAuthenticationToken.class)
                .map(auth -> {
                    var jwt = auth.getToken();
                    String clientId = jwt.getClaimAsString("azp");
                    return clientId != null ? clientId : jwt.getSubject();
                });
    }
}
