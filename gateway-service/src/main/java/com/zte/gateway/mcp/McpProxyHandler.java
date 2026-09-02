package com.zte.gateway.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zte.gateway.audit.ClientIpResolver;
import com.zte.gateway.mcp.approval.PendingApprovalService;
import com.zte.gateway.mcp.audit.McpAuditEvent;
import com.zte.gateway.mcp.audit.McpAuditService;
import com.zte.gateway.mcp.model.JsonRpcRequest;
import com.zte.gateway.mcp.model.JsonRpcResponse;
import com.zte.gateway.mcp.policy.McpPolicyEngine;
import com.zte.gateway.mcp.policy.McpCaller;
import com.zte.gateway.mcp.policy.PolicyDecision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
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
 * its result — deny, hold, or (masked) backend response — is injected back into
 * that same SSE stream; the POST itself only ever returns 202 Accepted, since the
 * real answer travels over SSE.
 *
 * <p><b>Stage 11 (ADR-011):</b> re-enables policy/backend enforcement, superseding
 * Stage 9 (ADR-010)'s dead-end stub — {@code McpPolicyEngine} is now
 * {@code YamlMcpPolicyEngine}, keyed on the per-agent grants in the YAML policy
 * document rather than a placeholder deny-list.
 *
 * <p><b>Stage 1 (ADR-019):</b> a HOLD decision doesn't forward to the backend at
 * all — {@link PendingApprovalService} parks the call for a human decision;
 * see {@link #process}.
 */
@Component
public class McpProxyHandler {

    private static final Logger log = LoggerFactory.getLogger(McpProxyHandler.class);
    private static final String PROCESS_ID = String.valueOf(ProcessHandle.current().pid());

    private final McpSessionManager sessionManager;
    private final McpPolicyEngine policyEngine;
    private final McpAuditService auditService;
    private final McpForwardService forwardService;
    private final com.zte.gateway.policy.def.PolicyDefaultsProperties policyDefaults;
    private final PendingApprovalService pendingApprovalService;
    private final ObjectMapper objectMapper;

    public McpProxyHandler(McpSessionManager sessionManager,
                            McpPolicyEngine policyEngine,
                            McpAuditService auditService,
                            McpForwardService forwardService,
                            com.zte.gateway.policy.def.PolicyDefaultsProperties policyDefaults,
                            PendingApprovalService pendingApprovalService,
                            ObjectMapper objectMapper) {
        this.sessionManager = sessionManager;
        this.policyEngine = policyEngine;
        this.auditService = auditService;
        this.forwardService = forwardService;
        this.policyDefaults = policyDefaults;
        this.pendingApprovalService = pendingApprovalService;
        this.objectMapper = objectMapper;
    }

    public Mono<ServerResponse> handleSse(ServerRequest request) {
        HttpAuditContext httpContext = HttpAuditContext.from(request);

        return currentIdentity(request).flatMap(identity -> {
            String agentId = identity.agentId();
            String sessionId = UUID.randomUUID().toString();
            log.info("MCP SSE session opened sessionId={} agentId={}", sessionId, agentId);

            // No policy decision happens here (any authenticated, cert-bearing caller may
            // open a session — the interesting security question is which tool calls it's
            // then allowed to make, audited separately by process() below), but the session
            // itself is still worth an audit row: otherwise "who opened a session and never
            // called a tool" is invisible. toolName/reason null distinguish this from a real
            // tool-call event.
            auditService.record(new McpAuditEvent(PROCESS_ID, agentId, null, "SSE_OPENED", Instant.now(),
                    sessionId, null, httpContext.traceId(), httpContext.clientIp(), httpContext.userAgent(),
                    identity.displayIdentity(), null));

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

        HttpAuditContext httpContext = HttpAuditContext.from(request);

        return currentIdentity(request)
                .flatMap(identity -> request.bodyToMono(JsonRpcRequest.class)
                        .flatMap(rpc -> process(sessionId, identity, httpContext, rpc)))
                .then(ServerResponse.accepted().build());
    }

    /**
     * Evaluates policy for one JSON-RPC call and emits the outcome (deny, held,
     * or backend result) into the caller's SSE session. Never surfaces an error
     * to the {@code POST /message} response itself — everything travels via SSE.
     */
    private Mono<Void> process(String sessionId, CallerIdentity identity, HttpAuditContext httpContext,
                                JsonRpcRequest rpc) {
        String agentId = identity.agentId();
        String toolName = rpc.toolName();
        String argumentsJson = serializeArguments(rpc.toolArguments());

        // ADR-039: "which tools exist" is not "call this tool". A caller that cannot
        // discover the menu cannot be refused a dish in a way anybody understands —
        // and for the chat console the refusal IS the demonstration, so the model is
        // shown every tool and finds out from the gate which ones it may use. The
        // listing is forwarded and audited; nothing about it reveals data, and every
        // actual call still goes through the decision below.
        if (rpc.toolName() == null && "tools/list".equals(rpc.method())) {
            log.debug("MCP LIST sessionId={} caller={}", sessionId, agentId);
            auditService.record(new McpAuditEvent(PROCESS_ID, agentId, "tools/list", "ALLOWED", Instant.now(),
                    sessionId, "Tool discovery", httpContext.traceId(), httpContext.clientIp(),
                    httpContext.userAgent(), identity.displayIdentity(), null));
            return forwardService.execute(identity.caller().acapKeys(), rpc).flatMap(response -> emit(sessionId, response));
        }

        PolicyDecision decision = policyEngine.evaluate(identity.caller(), toolName, rpc.toolArguments());

        return switch (decision.outcome()) {
            case DENY -> {
                log.warn("MCP DENY sessionId={} agentId={} tool={} reason={}",
                        sessionId, agentId, toolName, decision.reason());
                auditService.record(new McpAuditEvent(PROCESS_ID, agentId, toolName, "DENIED", Instant.now(),
                        sessionId, decision.reason(), httpContext.traceId(), httpContext.clientIp(),
                        httpContext.userAgent(), identity.displayIdentity(), argumentsJson));
                yield emit(sessionId, JsonRpcResponse.denied(rpc.id(), decision.reason()));
            }
            case HOLD -> {
                log.info("MCP HOLD sessionId={} agentId={} tool={} reason={}",
                        sessionId, agentId, toolName, decision.reason());
                yield pendingApprovalService.hold(sessionId, agentId, toolName, rpc, decision.reason(),
                                decision.routeTo(), httpContext.traceId(), httpContext.clientIp(),
                                httpContext.userAgent(), identity.displayIdentity())
                        .flatMap(approval -> {
                            auditService.record(new McpAuditEvent(PROCESS_ID, agentId, toolName, "HELD",
                                    Instant.now(), sessionId, decision.reason(), httpContext.traceId(),
                                    httpContext.clientIp(), httpContext.userAgent(), identity.displayIdentity(),
                                    argumentsJson));
                            return emit(sessionId,
                                    JsonRpcResponse.held(rpc.id(), approval.id().toString(), decision.reason()));
                        });
            }
            case ALLOW -> {
                log.debug("MCP ALLOW sessionId={} agentId={} tool={}", sessionId, agentId, toolName);
                yield forwardService.execute(identity.caller().acapKeys(), rpc)
                        .doOnNext(resp -> auditService.record(
                                new McpAuditEvent(PROCESS_ID, agentId, toolName, "ALLOWED", Instant.now(), sessionId,
                                        null, httpContext.traceId(), httpContext.clientIp(), httpContext.userAgent(),
                                        identity.displayIdentity(), argumentsJson)))
                        .flatMap(resp -> emit(sessionId, resp));
            }
        };
    }

    /**
     * The {@code tools/call} request's {@code params.arguments} as compact JSON —
     * "what was inside the message" for the Admin Console's Audit Trail Tool-name
     * tooltip (ADR: MCP audit row enrichment). Not masked: {@link DataMaskingFilter}
     * only ever applies to the backend's *response*, and these are the inputs the
     * caller itself supplied, not data pulled from the (potentially sensitive)
     * downstream system.
     */
    private String serializeArguments(Map<String, Object> arguments) {
        try {
            return objectMapper.writeValueAsString(arguments);
        } catch (Exception ex) {
            return String.valueOf(arguments);
        }
    }

    private Mono<Void> emit(String sessionId, JsonRpcResponse response) {
        return Mono.fromCallable(() -> objectMapper.writeValueAsString(response))
                .doOnNext(json -> sessionManager.emit(sessionId,
                        ServerSentEvent.<String>builder().event("message").data(json).build()))
                .then();
    }

    /**
     * The calling client's identity: {@code agentId} prefers {@code azp} (the
     * OAuth2 client_id — what a client-credentials-flow agent token carries,
     * and the same claim {@code RequestAuditFilter} already uses elsewhere in
     * this gateway), falling back to {@code sub} for tokens that don't carry
     * {@code azp}; {@code displayIdentity} is {@code preferred_username}
     * (e.g. {@code "service-account-agent-b"}, far more legible in the Admin
     * Console than a bare Keycloak UUID), falling back to {@code sub} if
     * absent — {@code originalUserObo} in the audit trail. MCP calls never
     * mint a downstream OBO token from this value (unlike REST traffic), so
     * unlike {@code RequestAuditFilter} there's no separate raw-{@code sub}
     * value that has to stay untouched elsewhere — this is its only use.
     */
    private Mono<CallerIdentity> currentIdentity(ServerRequest request) {
        return request.principal()
                .cast(JwtAuthenticationToken.class)
                .map(auth -> {
                    var jwt = auth.getToken();
                    String clientId = jwt.getClaimAsString("azp");
                    String preferredUsername = jwt.getClaimAsString("preferred_username");

                    // ADR-039: a person is recognised by the CLIENT their token came from,
                    // not by the presence of preferred_username — Keycloak puts that claim on
                    // service-account tokens too ("service-account-<client>"), so the first
                    // version of this check classified every agent as a human and silently
                    // dropped its ACAP profile. Same rule as ZteAuthorizationFilter uses, on
                    // purpose: one definition of "person" for the whole gateway.
                    if (policyDefaults.isUserClient(clientId)
                            && preferredUsername != null && !preferredUsername.isBlank()) {
                        McpCaller caller = McpCaller.user(preferredUsername, realmRoles(jwt), clientId);
                        return new CallerIdentity(caller.id(), preferredUsername, caller);
                    }
                    String agentId = clientId != null ? clientId : jwt.getSubject();
                    return new CallerIdentity(agentId, agentId, McpCaller.client(agentId));
                });
    }

    @SuppressWarnings("unchecked")
    private static java.util.List<String> realmRoles(org.springframework.security.oauth2.jwt.Jwt jwt) {
        java.util.Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
        return realmAccess == null ? java.util.List.of()
                : (java.util.List<String>) realmAccess.getOrDefault("roles", java.util.List.of());
    }

    private record CallerIdentity(String agentId, String displayIdentity,
                                   com.zte.gateway.mcp.policy.McpCaller caller) {}

    /**
     * The HTTP context an audited MCP event carries — {@code clientIp} via the
     * shared {@link ClientIpResolver} (same rule {@code RequestAuditFilter}
     * uses), {@code traceId} with the same {@code X-Request-Id}-or-mint
     * fallback, always from the specific request that triggered the event:
     * the {@code GET /sse} request for an {@code SSE_OPENED} event, the
     * {@code POST /message} request for a tool-call event — these are
     * different connections and may carry different proxy/forwarding
     * headers, so neither is reused for the other's audit row.
     */
    private record HttpAuditContext(String traceId, String clientIp, String userAgent) {
        static HttpAuditContext from(ServerRequest request) {
            HttpHeaders headers = request.headers().asHttpHeaders();

            String existingTraceId = headers.getFirst("X-Request-Id");
            String traceId = (existingTraceId != null && !existingTraceId.isBlank())
                    ? existingTraceId : UUID.randomUUID().toString();

            String clientIp = ClientIpResolver.resolve(
                    headers.getFirst("X-Forwarded-For"), request.remoteAddress().orElse(null));

            return new HttpAuditContext(traceId, clientIp, headers.getFirst(HttpHeaders.USER_AGENT));
        }
    }
}
