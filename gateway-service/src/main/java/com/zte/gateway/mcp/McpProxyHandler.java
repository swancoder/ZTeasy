package com.zte.gateway.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zte.gateway.mcp.audit.McpAuditEvent;
import com.zte.gateway.mcp.audit.McpAuditService;
import com.zte.gateway.mcp.mask.DataMaskingFilter;
import com.zte.gateway.mcp.model.JsonRpcRequest;
import com.zte.gateway.mcp.model.JsonRpcResponse;
import com.zte.gateway.mcp.policy.McpPolicyEngine;
import com.zte.gateway.mcp.policy.PolicyDecision;
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
 * MCP HTTP+SSE handshake, not a ZTeasy-specific invention). Every
 * {@code POST /message?sessionId=<id>} is evaluated by {@link McpPolicyEngine} and
 * its result — deny or (masked) backend response — is injected back into that same
 * SSE stream; the POST itself only ever returns 202 Accepted, since the real
 * answer travels over SSE.
 *
 * <p><b>Stage 11 (ADR-011):</b> re-enables policy/backend enforcement, superseding
 * Stage 9 (ADR-010)'s dead-end stub — {@code McpPolicyEngine} is now
 * {@code YamlMcpPolicyEngine}, keyed on the per-agent grants in the YAML policy
 * document rather than a placeholder deny-list.
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
     * Evaluates policy for one JSON-RPC call and emits the outcome (deny or
     * backend result) into the caller's SSE session. Never surfaces an error to
     * the {@code POST /message} response itself — everything travels via SSE.
     */
    private Mono<Void> process(String sessionId, String agentId, JsonRpcRequest rpc) {
        String toolName = rpc.toolName();
        PolicyDecision decision = policyEngine.evaluate(agentId, toolName, rpc.toolArguments());

        if (!decision.allowed()) {
            log.warn("MCP DENY sessionId={} agentId={} tool={} reason={}",
                    sessionId, agentId, toolName, decision.reason());
            auditService.record(new McpAuditEvent(PROCESS_ID, agentId, toolName, "DENIED", Instant.now(),
                    sessionId, decision.reason()));
            return emit(sessionId, JsonRpcResponse.denied(rpc.id(), decision.reason()));
        }

        log.debug("MCP ALLOW sessionId={} agentId={} tool={}", sessionId, agentId, toolName);
        return backendClient.forward(rpc)
                .map(dataMaskingFilter::mask)
                .doOnNext(resp -> auditService.record(
                        new McpAuditEvent(PROCESS_ID, agentId, toolName, "ALLOWED", Instant.now(), sessionId, null)))
                .flatMap(resp -> emit(sessionId, resp));
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
